"""Replace the text on a phone screen in a video shot with other (e.g. Persian) text.

The screen is treated as a plane. On a reference frame, its four corners map it to a
flat rectangle; text regions are cleaned there (inpainting) and new text is drawn.
Every frame of the shot is then matched to the reference frame (SIFT + RANSAC homography,
chained through the previous frame when a direct match is weak), the edited patches are
warped into place, brightness-matched, and anything covering the screen (a finger) is kept
on top by comparing the frame with the warped original reference.

  python screens.py preview  config.json out_dir          # edited flat screens to check text
  python screens.py render   config.json in.mp4 out.mp4   # whole video with the screens replaced

config.json: {"fonts": {"regular": path, "bold": path}, "shots": [{
   "name": "c", "ref": 51.9, "start": 48.8, "end": 52.2,
   "corners": [[x,y] TL, TR, BR, BL], "size": [W, H], "blur": 0.6,
   "regions": [{"box": [x0,y0,x1,y1], "lines": ["..."], "size": 26, "color": [r,g,b],
                "align": "right|center|left", "font": "regular|bold", "keep": false}]}]}
"""
import json
import subprocess
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, features


def load(path):
    return json.load(open(path, encoding="utf-8"))


def frame_at(cap, t, fps):
    cap.set(cv2.CAP_PROP_POS_FRAMES, round(t * fps))
    ok, f = cap.read()
    if not ok:
        raise SystemExit(f"no frame at {t}s")
    return f


def rect_matrix(shot):
    W, H = shot["size"]
    return cv2.getPerspectiveTransform(np.float32(shot["corners"]), np.float32([[0, 0], [W, 0], [W, H], [0, H]]))


def draw_lines(img_rgb, region, fonts):
    """Draw right-to-left text lines into the region box (PIL with raqm shaping)."""
    pil = Image.fromarray(img_rgb)
    draw = ImageDraw.Draw(pil)
    font = ImageFont.truetype(fonts[region.get("font", "regular")], region["size"], layout_engine=ImageFont.Layout.RAQM)
    x0, y0, x1, y1 = region["box"]
    lines = region["lines"]
    lh = region.get("line_height", region["size"] * 1.35)
    total = lh * len(lines)
    y = y0 + (y1 - y0 - total) / 2 + region.get("dy", 0)
    for line in lines:
        l, t, r, b = draw.textbbox((0, 0), line, font=font, direction="rtl", language="fa")
        w = r - l
        align = region.get("align", "right")
        pad = region.get("pad", 6)
        x = x1 - pad - w if align == "right" else x0 + pad if align == "left" else (x0 + x1 - w) / 2
        draw.text((x - l, y + (lh - (b - t)) / 2 - t), line, font=font, fill=tuple(region["color"]), direction="rtl", language="fa")
        y += lh
    return np.array(pil)


def edit_rect(rect_bgr, shot, fonts):
    """Clean the text out of each region and draw the new text. Returns (edited, mask)."""
    out = rect_bgr.copy()
    mask = np.zeros(rect_bgr.shape[:2], np.uint8)
    for region in shot["regions"]:
        x0, y0, x1, y1 = [int(v) for v in region["box"]]
        if not region.get("keep"):
            crop = out[y0:y1, x0:x1].astype(float)
            # the background inside a bubble or bar is a smooth gradient: fit a plane per colour channel to the
            # pixels near the region's median colour (text pixels are far from it) and fill the box with it
            med = np.median(crop.reshape(-1, 3), 0)
            dist = np.abs(crop - med).max(2)
            bg = dist < region.get("bg_tol", 28)
            yy, xx = np.mgrid[0:y1 - y0, 0:x1 - x0]
            A = np.stack([np.ones(bg.sum()), xx[bg], yy[bg], xx[bg] * yy[bg]], 1)
            fill = np.empty_like(crop)
            for ch in range(3):
                coef, *_ = np.linalg.lstsq(A, crop[..., ch][bg], rcond=None)
                fill[..., ch] = coef[0] + coef[1] * xx + coef[2] * yy + coef[3] * xx * yy
            # feather the edges of the box into the original so no rectangle shows
            e = region.get("feather", 3)
            ramp = np.minimum.reduce([xx + 1, yy + 1, x1 - x0 - xx, y1 - y0 - yy]).astype(float)
            a = np.clip(ramp / max(e, 1), 0, 1)[..., None]
            out[y0:y1, x0:x1] = np.clip(crop * (1 - a) + fill * a, 0, 255).astype(np.uint8)
        if region.get("lines"):
            rgb = cv2.cvtColor(out, cv2.COLOR_BGR2RGB)
            rgb = draw_lines(rgb, region, fonts)
            out = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        mask[y0:y1, x0:x1] = 255
    blur = shot.get("blur", 0.6)
    if blur > 0:
        soft = cv2.GaussianBlur(out, (0, 0), blur)
        out = np.where(mask[..., None] > 0, soft, out)
    return out, mask


def edited_reference(ref, shot, fonts):
    """The reference frame with the edited regions pasted back in perspective."""
    W, H = shot["size"]
    M = rect_matrix(shot)
    rect = cv2.warpPerspective(ref, M, (W, H), flags=cv2.INTER_CUBIC)
    edited, mask = edit_rect(rect, shot, fonts)
    Minv = np.linalg.inv(M)
    h, w = ref.shape[:2]
    back = cv2.warpPerspective(edited, Minv, (w, h), flags=cv2.INTER_CUBIC)
    m = cv2.warpPerspective(mask, Minv, (w, h), flags=cv2.INTER_LINEAR)
    m = cv2.GaussianBlur(m, (0, 0), 1.2)
    a = (m.astype(float) / 255)[..., None]
    return (ref * (1 - a) + back * a).astype(np.uint8), m, edited


def on_screen(kp, des, corners, H, grow=30):
    """Keep only the keypoints that fall on the screen (its corners projected by H), so frame-to-frame
    chaining follows the phone and not the background."""
    if des is None or H is None:
        return kp, des
    poly = cv2.perspectiveTransform(np.float32(corners).reshape(-1, 1, 2), H).reshape(-1, 2)
    c = poly.mean(0)
    poly = c + (poly - c) * (1 + grow / max(np.linalg.norm(poly - c, axis=1).mean(), 1))
    keep = [j for j, k in enumerate(kp) if cv2.pointPolygonTest(np.float32(poly), k.pt, False) >= 0]
    return [kp[j] for j in keep], des[keep] if keep else None


class Tracker:
    def __init__(self, ref, corners):
        self.sift = cv2.SIFT_create(4000)
        self.ref_gray = cv2.cvtColor(ref, cv2.COLOR_BGR2GRAY)
        poly = np.zeros(self.ref_gray.shape, np.uint8)
        cv2.fillConvexPoly(poly, np.int32(corners), 255)
        poly = cv2.dilate(poly, np.ones((15, 15), np.uint8))
        self.kp, self.des = self.sift.detectAndCompute(self.ref_gray, poly)
        self.matcher = cv2.BFMatcher()

    def match(self, kp1, des1, kp2, des2):
        if des1 is None or des2 is None or len(kp1) < 8 or len(kp2) < 8:
            return None, 0
        pairs = self.matcher.knnMatch(des1, des2, k=2)
        good = [m for m, n in (p for p in pairs if len(p) == 2) if m.distance < 0.75 * n.distance]
        if len(good) < 8:
            return None, 0
        src = np.float32([kp1[m.queryIdx].pt for m in good])
        dst = np.float32([kp2[m.trainIdx].pt for m in good])
        H, inl = cv2.findHomography(src, dst, cv2.RANSAC, 3.0)
        return H, int(inl.sum()) if inl is not None else 0


def render(cfg_path, src, dst):
    cfg = load(cfg_path)
    fonts = cfg["fonts"]
    cap = cv2.VideoCapture(src)
    fps = cap.get(cv2.CAP_PROP_FPS)
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w, h = int(cap.get(3)), int(cap.get(4))
    plans = {}
    for shot in cfg["shots"]:
        ref = frame_at(cap, shot["ref"], fps)
        edited_ref, emask, _ = edited_reference(ref, shot, fonts)
        tr = Tracker(ref, shot["corners"])
        f0, f1 = round(shot["start"] * fps), min(n - 1, round(shot["end"] * fps))
        Hs, feats, prev_H = {}, {}, None
        cap.set(cv2.CAP_PROP_POS_FRAMES, f0)
        for i in range(f0, f1 + 1):
            ok, f = cap.read()
            if not ok:
                break
            g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
            kp, des = tr.sift.detectAndCompute(g, None)
            feats[i] = (kp, des)
            H, inl = tr.match(tr.kp, tr.des, kp, des)
            if inl < shot.get("min_inliers", 30) and prev_H is not None and i - 1 in feats:
                Hp, inlp = tr.match(*on_screen(*feats[i - 1], shot["corners"], prev_H), kp, des)
                H = Hp @ prev_H if inlp >= 20 else None
                inl = -inlp
            elif inl < shot.get("min_inliers", 30):
                H = None
            if H is not None:
                Hs[i] = H
            prev_H = H
            print(f"  {shot['name']} frame {i} ({i / fps:.2f}s) inliers {inl}{'' if H is not None else ' SKIP'}", file=sys.stderr)
        # frames before the first good match (phone still small or moving in): chain backwards frame to frame
        if Hs:
            for i in range(min(Hs) - 1, f0 - 1, -1):
                Hb, inlb = tr.match(*on_screen(*feats[i + 1], shot["corners"], Hs[i + 1]), *feats[i])
                if inlb < 20:
                    break
                Hs[i] = Hb @ Hs[i + 1]
                print(f"  {shot['name']} frame {i} ({i / fps:.2f}s) backward inliers {inlb}", file=sys.stderr)
        # smooth the projected corners over time to remove jitter, then rebuild each homography
        idx = sorted(Hs)
        c0 = np.float32(shot["corners"]).reshape(-1, 1, 2)
        proj = {i: cv2.perspectiveTransform(c0, Hs[i]).reshape(-1) for i in idx}
        smooth = {}
        for i in idx:
            near = [proj[j] for j in range(i - 2, i + 3) if j in proj]
            smooth[i] = cv2.getPerspectiveTransform(c0.reshape(-1, 2), np.float32(np.mean(near, 0)).reshape(-1, 2))
        plans.update({i: (shot, ref, edited_ref, emask, smooth[i]) for i in idx})

    enc = subprocess.Popen(
        [cfg.get("ffmpeg", "ffmpeg"), "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w}x{h}", "-r", str(fps),
         "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "12", "-pix_fmt", "yuv420p", dst],
        stdin=subprocess.PIPE)
    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
    for i in range(n):
        ok, f = cap.read()
        if not ok:
            break
        if i in plans:
            shot, ref, edited_ref, emask, H = plans[i]
            wr = cv2.warpPerspective(ref, H, (w, h), flags=cv2.INTER_LINEAR)
            we = cv2.warpPerspective(edited_ref, H, (w, h), flags=cv2.INTER_LINEAR)
            wm = cv2.warpPerspective(emask, H, (w, h), flags=cv2.INTER_LINEAR).astype(float) / 255
            screen = np.zeros((h, w), np.uint8)
            cv2.fillConvexPoly(screen, np.int32(cv2.perspectiveTransform(np.float32(shot["corners"]).reshape(-1, 1, 2), H).reshape(-1, 2)), 255)
            sel = screen > 0
            if sel.sum() > 500:
                gain = np.median(f[sel].astype(float), 0) / np.maximum(np.median(wr[sel].astype(float), 0), 1)
                gain = np.clip(gain, 0.5, 1.8)
                wr = np.clip(wr * gain, 0, 255)
                we = np.clip(we * gain, 0, 255)
                diff = np.abs(f.astype(float) - wr).max(2)
                occ = (diff > shot.get("occlusion", 45)).astype(np.uint8) * 255
                occ = cv2.morphologyEx(occ, cv2.MORPH_OPEN, np.ones((7, 7), np.uint8))
                occ = cv2.dilate(occ, np.ones((9, 9), np.uint8))
                occ = cv2.GaussianBlur(occ, (0, 0), 3).astype(float) / 255
                a = (wm * (1 - occ))[..., None]
                f = (f * (1 - a) + we * a).astype(np.uint8)
        enc.stdin.write(f.tobytes())
    enc.stdin.close()
    enc.wait()


def preview(cfg_path, src, out_dir):
    cfg = load(cfg_path)
    cap = cv2.VideoCapture(src)
    fps = cap.get(cv2.CAP_PROP_FPS)
    for shot in cfg["shots"]:
        ref = frame_at(cap, shot["ref"], fps)
        er, _, edited = edited_reference(ref, shot, cfg["fonts"])
        cv2.imwrite(f"{out_dir}/{shot['name']}-flat.png", edited)
        cv2.imwrite(f"{out_dir}/{shot['name']}-ref.png", er)


if __name__ == "__main__":
    assert features.check("raqm"), "Pillow needs raqm for Persian shaping"
    mode = sys.argv[1]
    if mode == "preview":
        preview(sys.argv[2], sys.argv[3], sys.argv[4])
    elif mode == "render":
        render(sys.argv[2], sys.argv[3], sys.argv[4])
