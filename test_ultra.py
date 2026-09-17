import os, shutil
from ultralytics import YOLO

SRC = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\app\src\main\assets"
DIR = r"D:\deepseek-harness-master\Epicseven\E7ShopAssistant\e7sa_ncnn_model"
os.makedirs(DIR, exist_ok=True)
shutil.copy(os.path.join(SRC, "e7sa_yolo.ncnn.param"), os.path.join(DIR, "model.ncnn.param"))
shutil.copy(os.path.join(SRC, "e7sa_yolo.ncnn.bin"), os.path.join(DIR, "model.ncnn.bin"))

model = YOLO(DIR)
print("names:", model.names)
res = model.predict(r"D:\deepseek-harness-master\newe7sa\E7SA_Dataset\yolo\images\bookmark_buyable_row.jpg",
                    imgsz=640, conf=0.25, verbose=False)
for r in res:
    print("boxes:", r.boxes.xyxy)
    print("cls:", r.boxes.cls)
    print("conf:", r.boxes.conf)
