import ncnn, cv2, numpy as np

PARAM = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\e7sa_yolo.ncnn.param"
BIN = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\e7sa_yolo.ncnn.bin"
IMG = r"D:\deepseek-harness-master\newe7sa\E7SA_Dataset\yolo\images\bookmark_buyable_row.jpg"

net = ncnn.Net(); net.opt.num_threads = 1
net.load_param(PARAM); net.load_model(BIN)
bgr = cv2.imread(IMG)
img_h, img_w = bgr.shape[:2]

# ultralytics square letterbox: scale to 640x640 square, centered pad
T = 640
r = T / max(img_w, img_h)
w = int(round(img_w * r)); h = int(round(img_h * r))
scale = w / img_w   # == h / img_h
dw = T - w; dh = T - h
top, left = dh // 2, dw // 2
print("resized", w, h, "scale", scale, "pad top", top, "left", left)

resized = cv2.resize(bgr, (w, h))
padded = cv2.copyMakeBorder(resized, top, dh - top, left, dw - left, cv2.BORDER_CONSTANT, value=(114, 114, 114))
mat = ncnn.Mat.from_pixels(padded, ncnn.Mat.PixelType.PIXEL_BGR2RGB, T, T)
mat.substract_mean_normalize([0, 0, 0], [1/255.0, 1/255.0, 1/255.0])
ex = net.create_extractor(); ex.input("in0", mat)
ret, out = ex.extract("out0")
print("dims", out.dims, "w", out.w, "h", out.h, "c", out.c)

data = np.array(out, dtype=np.float32).reshape(out.h, out.w)  # ch x anchor
x0 = data[0]; y0 = data[1]; x1 = data[2]; y1 = data[3]; p0 = data[4]; p1 = data[5]

hits = []
for j in range(out.w):
    s0, s1 = float(p0[j]), float(p1[j])
    cls, score = (0, s0) if s0 >= s1 else (1, s1)
    if score > 0.25:
        hits.append((score, cls, j, x0[j], y0[j], x1[j], y1[j]))
hits.sort(reverse=True)
print("hits>0.25", len(hits), "p0max", float(p0.max()), "p1max", float(p1.max()))
for score, cls, j, a, b, c, d in hits[:8]:
    print(f"cls={cls} prob={score:.3f} raw=({a:.1f},{b:.1f},{c:.1f},{d:.1f})")
