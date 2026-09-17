# Local PP-OCRv5 (ncnn) for PC-side diagnosis & UI automation.
# Faithful port of app/src/main/jni/ppocrv5.cpp + e7ocr.cpp.
import re
import numpy as np
import cv2
import ncnn

ASSETS = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets"
JNI = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\jni"

_det = None
_rec = None
_dict = None


def load():
    global _det, _rec, _dict
    if _det is not None:
        return
    _det = ncnn.Net(); _det.opt.num_threads = 1
    _det.load_param(ASSETS + r"\PP_OCRv5_mobile_det.ncnn.param")
    _det.load_model(ASSETS + r"\PP_OCRv5_mobile_det.ncnn.bin")
    _rec = ncnn.Net(); _rec.opt.num_threads = 1
    _rec.load_param(ASSETS + r"\PP_OCRv5_mobile_rec.ncnn.param")
    _rec.load_model(ASSETS + r"\PP_OCRv5_mobile_rec.ncnn.bin")
    header = open(JNI + r"\ppocrv5_dict.h", encoding="utf-8").read()
    _dict = re.findall(r'"((?:[^"\\]|\\.)*)"', header[header.index("character_dict[]"):])
    print("ocr ready, dict size", len(_dict))


def detect_boxes(bgr):
    """Port of PPOCRv5::detect. Returns list of (rrect(cx,cy,w,h,angle), orientation)."""
    H, W = bgr.shape[:2]
    target_size = 640  # PPOCRv5 ctor
    w, h = W, H
    scale = 1.0
    if max(w, h) > target_size:
        if w > h:
            scale = target_size / w; w = target_size; h = int(h * scale)
        else:
            scale = target_size / h; h = target_size; w = int(w * scale)
    wpad = (w + 31) // 32 * 32 - w
    hpad = (h + 31) // 32 * 32 - h
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (w, h))
    padded = cv2.copyMakeBorder(resized, hpad // 2, hpad - hpad // 2, wpad // 2, wpad - wpad // 2,
                                cv2.BORDER_CONSTANT, value=(0, 0, 0))
    mat = ncnn.Mat.from_pixels(padded, ncnn.Mat.PixelType.PIXEL_RGB2BGR, padded.shape[1], padded.shape[0])
    mat.substract_mean_normalize([0.485 * 255, 0.456 * 255, 0.406 * 255],
                                 [1 / (0.229 * 255), 1 / (0.224 * 255), 1 / (0.225 * 255)])
    ex = _det.create_extractor(); ex.input("in0", mat)
    ret, out = ex.extract("out0")
    pred = np.array(out).reshape(out.h, out.w)
    bitmap = (pred * 255 > 0.3 * 255).astype(np.uint8) * 255
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for contour in contours[:1000]:
        if len(contour) <= 2:
            continue
        mask = np.zeros(pred.shape, dtype=np.uint8)
        cv2.drawContours(mask, [contour], -1, 255, -1)
        score = (pred * (mask > 0)).sum() / max((mask > 0).sum(), 1)
        if score < 0.6:
            continue
        (cx, cy), (rw, rh), angle = cv2.minAreaRect(contour)
        if max(rw, rh) < 3 * scale:
            continue
        # orientation + angle normalization (faithful port)
        orientation = 0
        if -30 <= angle <= 30 and rh > rw * 2.7:
            orientation = 1
        if (angle <= -60 or angle >= 60) and rw > rh * 2.7:
            orientation = 1
        if angle < -30:
            angle += 180
        if orientation == 0 and angle < 30:
            angle += 90
            rw, rh = rh, rw
        if orientation == 1 and angle >= 60:
            angle -= 90
            rw, rh = rh, rw
        # enlarge
        rh = rh + rw * (1.95 - 1)
        rw = rw * 1.95
        cx = (cx - wpad / 2) / scale
        cy = (cy - hpad / 2) / scale
        rw = rw / scale
        rh = rh / scale
        boxes.append(((cx, cy, rw, rh, angle), orientation))
    return boxes


def rec_text(bgr, box):
    """Port of PPOCRv5::recognize: rotate crop -> rec net -> CTC greedy."""
    (cx, cy, rw, rh, angle), orientation = box
    target_height = 48
    target_width = max(4, int(rh * target_height / rw))
    rrect = ((cx, cy), (rw, rh), angle)
    corners = cv2.boxPoints(rrect)
    if orientation == 0:
        src = np.float32([corners[0], corners[1], corners[3]])
    else:
        src = np.float32([corners[2], corners[3], corners[1]])
    dst = np.float32([[0, 0], [target_width, 0], [0, target_height]])
    M = cv2.getAffineTransform(src, dst)
    roi = cv2.warpAffine(bgr, M, (target_width, target_height),
                         flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    mat = ncnn.Mat.from_pixels(roi, ncnn.Mat.PixelType.PIXEL_BGR, roi.shape[1], roi.shape[0])
    mat.substract_mean_normalize([127.5, 127.5, 127.5], [1 / 127.5, 1 / 127.5, 1 / 127.5])
    ex = _rec.create_extractor(); ex.input("in0", mat)
    ret, out = ex.extract("out0")
    pred = np.array(out).reshape(out.h, out.w)  # time x classes
    text = []
    last = -1
    for t in range(pred.shape[0]):
        idx = int(np.argmax(pred[t]))
        if idx == last:
            continue
        last = idx
        if idx <= 0:
            continue
        if idx - 1 >= len(_dict):
            continue
        text.append(_dict[idx - 1])
    return "".join(text)


def ocr(bgr):
    """Full OCR: returns list of (text, cx, cy) in original pixels."""
    load()
    out = []
    for box in detect_boxes(bgr):
        t = rec_text(bgr, box)
        if t:
            (cx, cy, rw, rh, angle), _ = box
            out.append((t, cx, cy))
    return out


if __name__ == "__main__":
    import sys
    img = cv2.imread(sys.argv[1])
    for t, cx, cy in ocr(img):
        print(f"{t} @ ({cx:.0f},{cy:.0f})")
