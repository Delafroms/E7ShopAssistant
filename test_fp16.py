import ncnn, cv2, numpy as np

DET_P = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.param"
DET_B = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets\PP_OCRv5_mobile_det.ncnn.bin"
IMG = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\diag_frames\diag_frames\frame_9_1788529031789.png"

def run(fp16):
    net = ncnn.Net()
    net.opt.num_threads = 1
    if fp16:
        net.opt.use_fp16_packed = True
        net.opt.use_fp16_storage = True
        net.opt.use_fp16_arithmetic = True
    net.load_param(DET_P)
    net.load_model(DET_B)
    bgr = cv2.imread(IMG); H, W = bgr.shape[:2]
    target_size = 640
    w, h = W, H; scale = 1.0
    if max(w, h) > target_size:
        if w > h: scale = target_size/w; w = target_size; h = int(h*scale)
        else: scale = target_size/h; h = target_size; w = int(w*scale)
    wpad = (w+31)//32*32-w; hpad = (h+31)//32*32-h
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (w, h))
    padded = cv2.copyMakeBorder(resized, hpad//2, hpad-hpad//2, wpad//2, wpad-wpad//2, cv2.BORDER_CONSTANT, value=(0,0,0))
    mat = ncnn.Mat.from_pixels(padded, ncnn.Mat.PixelType.PIXEL_RGB2BGR, padded.shape[1], padded.shape[0])
    mat.substract_mean_normalize([0.485*255,0.456*255,0.406*255],[1/(0.229*255),1/(0.224*255),1/(0.225*255)])
    ex = net.create_extractor(); ex.input("in0", mat)
    ret, out = ex.extract("out0")
    pred = np.array(out).reshape(out.h, out.w)
    bitmap = (pred*255 > 0.3*255).astype(np.uint8)*255
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    n = 0
    for c in contours[:1000]:
        if len(c) <= 2: continue
        mask = np.zeros(pred.shape, dtype=np.uint8); cv2.drawContours(mask, [c], -1, 255, -1)
        score = (pred*(mask>0)).sum()/max((mask>0).sum(),1)
        if score < 0.6: continue
        r = cv2.minAreaRect(c)
        if max(r[1]) < 3*scale: continue
        n += 1
    return n, float(pred.min()), float(pred.max())

for fp16 in (False, True):
    try:
        n, mn, mx = run(fp16)
        print(f"fp16={fp16}: boxes={n} pred[min={mn:.4f} max={mx:.4f}]")
    except Exception as e:
        print(f"fp16={fp16}: ERROR {e}")
