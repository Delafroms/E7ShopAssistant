import sys, io, subprocess, time

sys.path.insert(0, r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant')
ADB = r'D:\Android Studio\Android custom\platform-tools\adb.exe'

def sh(*a):
    return subprocess.run([ADB, 'shell', *a], capture_output=True, text=True).stdout

def snap():
    sh('screencap', '-p', '/sdcard/t2.png')
    subprocess.run([ADB, 'pull', '/sdcard/t2.png', r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant\t2.png'],
                   capture_output=True)

def find_btn(*keys):
    import cv2
    from pp_ocr import ocr
    img = cv2.imread(r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant\t2.png')
    for t, cx, cy in ocr(img):
        for k in keys:
            if k in t:
                return int(cx), int(cy)
    return None

subprocess.run([ADB, 'shell', 'am', 'start', '-n', 'com.e7.shop/.SplashActivity'], capture_output=True)
time.sleep(4)
sh('input', 'swipe', '1400', '1000', '1400', '250', '400')
time.sleep(2)
snap()
p = find_btn('停止')
if p is None:
    sh('input', 'swipe', '1400', '250', '1400', '1100', '400')
    time.sleep(2)
    snap()
    p = find_btn('停止')
if p is None:
    print('NOBUTTON')
    sys.exit(1)
print('STOP BTN', p[0], p[1])
subprocess.run([ADB, 'shell', 'input', 'tap', str(p[0]), str(p[1])], capture_output=True)
time.sleep(3)
out = subprocess.run([ADB, 'logcat', '-d', '-s', 'E7SA.State'], capture_output=True, text=True).stdout
tail = out.strip().splitlines()[-3:]
print('last state logs:', tail)
