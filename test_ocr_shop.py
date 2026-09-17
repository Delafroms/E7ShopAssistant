import ncnn, cv2, numpy as np

# PP-OCRv5 det+rec, same models the app ships
DET_P = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.param"
DET_B = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.bin"
REC_P = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_rec.ncnn.param"
REC_B = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_rec.ncnn.bin"
IMG = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\shop_now.png"

det = ncnn.Net(); det.load_param(DET_P); det.load_model(DET_B)
rec = ncnn.Net(); rec.load_param(REC_P); rec.load_model(REC_B)

bgr = cv2.imread(IMG)
H, W = bgr.shape[:2]
print("image", W, "x", H)

# det: letterbox to 960 (target_size used in ppocrv5.cpp), mean/norm as in C++
ts = 960
scale = 1.0
w, h = W, H
if max(w, h) > ts:
    if w > h:
        scale = ts / w; w = ts; h = int(h * scale)
    else:
        scale = ts / h; h = ts; w = int(w * scale)
wpad = (w + 31) // 32 * 32 - w
hpad = (h + 31) // 32 * 32 - h
print("det input", w, h, "pad", wpad, hpad)
resized = cv2.resize(bgr, (w, h))
padded = cv2.copyMakeBorder(resized, hpad//2, hpad-hpad//2, wpad//2, wpad-wpad//2, cv2.BORDER_CONSTANT, value=(0,0,0))
mat = ncnn.Mat.from_pixels(padded, ncnn.Mat.PixelType.PIXEL_BGR2RGB, padded.shape[1], padded.shape[0])
mean = [0.485*255, 0.456*255, 0.406*255]
norm = [1/(0.229*255), 1/(0.224*255), 1/(0.225*255)]
mat.substract_mean_normalize(mean, norm)
ex = det.create_extractor(); ex.input("in0", mat)
ret, out = ex.extract("out0")
pred = np.array(out).reshape(out.h, out.w)
print("det out", out.w, out.h, "min", pred.min(), "max", pred.max())

# threshold binary like C++
thr = 0.3 * 255
binary = (pred * 255 > thr).astype(np.uint8) * 255

# find connected components -> boxes (simplified: bounding boxes of components)
n, labels, stats, cents = cv2.connectedComponentsWithStats(binary, 8)
print("components", n)
boxes = []
for i in range(1, n):
    x, y, bw, bh, area = stats[i]
    if area < (3*scale)**2:
        continue
    # unpad/unscale back to original pixels
    x0 = (x - wpad//2) / scale
    y0 = (y - hpad//2) / scale
    x1 = (x + bw - wpad//2) / scale
    y1 = (y + bh - hpad//2) / scale
    boxes.append((x0, y0, x1, y1))

boxes.sort(key=lambda b: (b[1], b[0]))
print("boxes", len(boxes))

# rec: each box -> crop -> resize to (3,48,320)-ish (ppocrv5 rec target)
for i, (x0, y0, x1, y1) in enumerate(boxes):
    if i > 60:
        break
    crop = bgr[int(y0):int(y1), int(x0):int(x1)]
    if crop.size == 0:
        continue
    # rec expects BGR 3x48xW with letterbox to 48 height, width multiple of 32? use simple resize h=48
    rh = 48
    rw = max(8, int(crop.shape[1] * rh / crop.shape[0]))
    rcrop = cv2.resize(crop, (rw, rh))
    # pad width to 320? ppocrv5 rec target_size_w=320
    if rw < 320:
        rp = np.zeros((rh, 320, 3), dtype=np.uint8)
        rp[:, :rw] = rcrop
    else:
        rp = rcrop[:, :320]
    m = ncnn.Mat.from_pixels(rp, ncnn.Mat.PixelType.PIXEL_BGR2RGB, rp.shape[1], rp.shape[0])
    m.substract_mean_normalize([127.5,127.5,127.5], [1/127.5,1/127.5,1/127.5])
    ex2 = rec.create_extractor(); ex2.input("in0", m)
    r, o = ex2.extract("out0")
    if r != 0:
        print("rec fail", i); continue
    arr = np.array(o)
    print("box", i, "shape", arr.shape)
