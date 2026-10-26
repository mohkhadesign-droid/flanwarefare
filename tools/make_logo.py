import sys, json, zipfile, hashlib, os
from PIL import Image

G = {
'A':["01110","10001","10001","11111","10001","10001","10001"],
'D':["11110","10001","10001","10001","10001","10001","11110"],
'E':["11111","10000","10000","11110","10000","10000","11111"],
'G':["01111","10000","10000","10011","10001","10001","01111"],
'H':["10001","10001","10001","11111","10001","10001","10001"],
'I':["11111","00100","00100","00100","00100","00100","11111"],
'N':["10001","11001","10101","10011","10001","10001","10001"],
'O':["01110","10001","10001","10001","10001","10001","01110"],
'P':["11110","10001","10001","11110","10000","10000","10000"],
'R':["11110","10001","10001","11110","10100","10010","10001"],
'T':["11111","00100","00100","00100","00100","00100","00100"],
'X':["10001","10001","01010","00100","01010","10001","10001"],
' ':["00000"]*7,
}

def mask(word, u):
    w = len(word)*6*u - u
    h = 7*u
    m = [[0]*w for _ in range(h)]
    x0 = 0
    for ch in word:
        g = G[ch]
        for gy,row in enumerate(g):
            for gx,c in enumerate(row):
                if c=='1':
                    for yy in range(u):
                        for xx in range(u):
                            m[gy*u+yy][x0+gx*u+xx]=1
        x0 += 6*u
    return m, w, h

def draw(img, word, u, ox, oy, face_top, face, face_bot, depth_col, depth):
    m,w,h = mask(word,u)
    px = img.load()
    W,H = img.size
    def setp(x,y,c):
        if 0<=x<W and 0<=y<H: px[x,y]=c
    # extrusion (3D depth) down-right
    for d in range(depth,0,-1):
        for y in range(h):
            for x in range(w):
                if m[y][x]: setp(ox+x+d, oy+y+d, depth_col)
    # outline: everything around face+depth
    occ = set()
    for y in range(h):
        for x in range(w):
            if m[y][x]:
                for d in range(depth+1): occ.add((ox+x+d, oy+y+d))
    for (x,y) in list(occ):
        for dx in (-1,0,1):
            for dy in (-1,0,1):
                if (x+dx,y+dy) not in occ:
                    setp(x+dx,y+dy,(16,16,24,255))
    for (x,y) in occ:
        if px[x,y][3]==0: setp(x,y,depth_col)
    # face with vertical shading
    for y in range(h):
        for x in range(w):
            if m[y][x]:
                t = y / max(1,h-1)
                c = face_top if t < 0.3 else (face if t < 0.75 else face_bot)
                setp(ox+x, oy+y, c)
    return w+depth, h+depth

W, H = 132, 56
img = Image.new("RGBA",(W,H),(0,0,0,0))
cy = lambda r,g,b:(r,g,b,255)
# THE (small, gold)
mw,_,_ = None,None,None
m,w,h = mask("THE",1)
draw(img,"THE",1,(W-w)//2-1,1,cy(255,225,120),cy(255,190,40),cy(220,140,20),cy(120,70,10),1)
# GRAND (big, cyan)
m,w,h = mask("GRAND",3)
draw(img,"GRAND",3,(W-w-2)//2,11,cy(150,250,255),cy(60,220,240),cy(30,170,200),cy(10,80,110),2)
# EXPEDITION (stone)
m,w,h = mask("EXPEDITION",2)
draw(img,"EXPEDITION",2,(W-w-2)//2,37,cy(245,245,245),cy(205,205,210),cy(160,160,170),cy(70,70,80),2)
img.save(sys.argv[1] if len(sys.argv) > 1 else "logo.png")
print("ok", img.size)
