import sys, io, subprocess, time, re

sys.path.insert(0, r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant')
ADB = r'D:\Android Studio\Android custom\platform-tools\adb.exe'

def sh(*a):
    return subprocess.run([ADB, 'shell', *a], capture_output=True, text=True).stdout

def snap():
    sh('screencap', '-p', '/sdcard/t.png')
    subprocess.run([ADB, 'pull', '/sdcard/t.png', r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant\t.png'],
                   capture_output=True)

def find_btn(*keys):
    import cv2
    from pp_ocr import ocr
    img = cv2.imread(r'D:\deepseek-harness-master\Epicseven\E7ShopAssistant\t.png')
    for t, cx, cy in ocr(img):
        for k in keys:
            if k in t:
                return int(cx), int(cy)
    return None

def tap(x, y):
    sh('input', 'tap', str(x), str(y))

# bring app to front
subprocess.run([ADB, 'shell', 'am', 'start', '-n', 'com.e7.shop/.SplashActivity'], capture_output=True)
time.sleep(4)
# scroll content down a bit to reveal controls
sh('input', 'swipe', '1400', '1000', '1400', '250', '400')
time.sleep(2)
snap()
p = find_btn('开始', '重新开始')
if p is None:
    # maybe already at top: scroll up first then retry
    sh('input', 'swipe', '1400', '250', '1400', '1100', '400')
    time.sleep(2)
    snap()
    p = find_btn('开始', '重新开始')
if p is None:
    print('NOBUTTON')
    sys.exit(1)
print('BUTTON', p[0], p[1])
tap(p[0], p[1])
time.sleep(2)
# verify RESET in logcat
out = subprocess.run([ADB, 'logcat', '-d', '-s', 'E7SA.State'], capture_output=True, text=True).stdout
if 'RESET' in out:
    print('STARTED')
else:
    print('NOSTART')
