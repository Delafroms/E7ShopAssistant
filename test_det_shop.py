import ncnn, cv2, numpy as np

DET_P = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.param"
DET_B = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.bin"
IMG = r"D:\deepseek-harness-master\newe7sa\E7SA_Dataset\yolo\images\bookmark_buyable_row.jpg"

net = ncnn.Net(); net.opt.num_threads = 1
net.load_param(DET_P); net.load_model(DET_B)

bgr = cv2.imread(IMG)
H, W = bgr.shape[:2]
print("image", W, "x", H)

# replicate ppocrv5.cpp detect() preprocessing exactly
target_size = 960
img_w, img_h = W, H
w, h = img_w, img_h
scale = 1.0
if max(w, h) > target_size:
    if w > h:
        scale = target_size / w; w = target_size; h = int(h * scale)
    else:
        scale = target_size / h; h = target_size; w = int(w * scale)
wpad = (w + 31) // 32 * 32 - w
hpad = (h + 31) // 32 * 32 - h
print("resized", w, h, "scale", scale, "pad", wpad, hpad)

rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
resized = cv2.resize(rgb, (w, h))
padded = cv2.copyMakeBorder(resized, hpad//2, hpad-hpad//2, wpad//2, wpad-wpad//2, cv2.BORDER_CONSTANT, value=(0,0,0))
mat = ncnn.Mat.from_pixels(padded, ncnn.Mat.PixelType.PIXEL_RGB2BGR, padded.shape[1], padded.shape[0])
mean = [0.485*255, 0.456*255, 0.406*255]
norm = [1/(0.229*255), 1/(0.224*255), 1/(0.225*255)]
mat.substract_mean_normalize(mean, norm)
ex = net.create_extractor(); ex.input("in0", mat)
ret, out = ex.extract("out0")
pred = np.array(out).reshape(out.h, out.w)
print("det out", out.w, out.h, "min", pred.min(), "max", pred.max())

# threshold binary like C++
thr = 0.3 * 255
bitmap = (pred * 255 > thr).astype(np.uint8) * 255

# findContours + score filter like C++
contours, hierarchy = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
print("raw contours", len(contours))
boxes = []
for contour in contours[:1000]:
    if len(contour) <= 2:
        continue
    # contour_score: mean of pred over contour mask
    mask = np.zeros(pred.shape, dtype=np.uint8)
    cv2.drawContours(mask, [contour], -1, 255, -1)
    score = (pred * (mask > 0)).sum() / max((mask > 0).sum(), 1)
    if score < 0.6:
        continue
    rrect = cv2.minAreaRect(contour)
    maxwh = max(rrect[1])
    if maxwh < 3 * scale:
        continue
    boxes.append(rrect)
print("boxes after filters:", len(boxes))
for r in boxes[:12]:
    print("  center", tuple(round(v,1) for v in r[0]), "size", tuple(round(v,1) for v in r[1]), "angle", round(r[2],1))
