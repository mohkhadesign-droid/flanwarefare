# Geometry check of FlagRenderer's cuboids (same numbers as the Java code), not an in-game render.
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d.art3d import Poly3DCollection
STONE="#8a8a8c"; STONE2="#a6a6a8"; WOOD="#4a3220"; DARK="#2e2a28"; GOLD="#f2c230"
boxes=[(0,0,0,16,2,16,STONE),(4,2,4,12,6,12,STONE2),(7,6,7,9,94,9,WOOD),
       (-2,90,7,18,92.5,10.6,DARK),(-2.5,89.5,6.5,0.5,93,11,DARK),(15.5,89.5,6.5,18.5,93,11,DARK),
       (6,94,6,10,97,10,GOLD),(7,97,7,9,99,9,GOLD)]
for i in range(4):
    x=1.25+i*4; boxes.append((x,88,10.1,x+1.5,90,10.6,DARK))
def cub(x0,y0,z0,x1,y1,z1):
    # matplotlib: z up -> map MC y to plot z, MC z to plot y
    P=lambda x,y,z:(x,z,y)
    v=[P(x0,y0,z0),P(x1,y0,z0),P(x1,y1,z0),P(x0,y1,z0),P(x0,y0,z1),P(x1,y0,z1),P(x1,y1,z1),P(x0,y1,z1)]
    f=[[0,1,2,3],[4,5,6,7],[0,1,5,4],[2,3,7,6],[1,2,6,5],[0,3,7,4]]
    return [[v[i] for i in q] for q in f]
def fabric_faces():
    # fabric 16x32, z 9.3..10.1, y 58..90; front: black field, red border, gold hourglass (example design)
    faces=[]; cols=[]
    import math
    for gx in range(16):
        for gy in range(32):
            x0,y0=gx,58+gy
            c="#111111"
            if gx<1.5 or gx>14 or gy<1.5 or gy>30: c="#b01218"
            else:
                yy=gy-16; w=abs(yy)/14*6.5
                if 3<=abs(yy)<=14 and abs(gx-7.5)<=w: c="#f0c020"
                if abs(math.hypot(gx-7.5,yy)-3.6)<0.7: c="#f0c020"
            faces.append([(x0,10.1,y0),(x0+1,10.1,y0),(x0+1,10.1,y0+1),(x0,10.1,y0+1)]); cols.append(c)
            faces.append([(x0,9.3,y0),(x0+1,9.3,y0),(x0+1,9.3,y0+1),(x0,9.3,y0+1)]); cols.append("#111111" if 1.5<=gx<=14 and 1.5<=gy<=30 else "#b01218")
    return faces,cols
fig=plt.figure(figsize=(10,7.5),dpi=110)
for k,(az,title) in enumerate([(120,"front"),(-60,"back")]):
    ax=fig.add_subplot(1,2,k+1,projection="3d")
    for b in boxes:
        ax.add_collection3d(Poly3DCollection(cub(*b[:6]),facecolors=b[6],edgecolors="#00000040",linewidths=0.3))
    f,c=fabric_faces(); ax.add_collection3d(Poly3DCollection(f,facecolors=c,linewidths=0))
    ax.set_xlim(-8,24); ax.set_ylim(-8,24); ax.set_zlim(0,104); ax.set_box_aspect((32,32,104))
    ax.view_init(elev=12,azim=az); ax.set_axis_off(); ax.set_title(title+" (geometry check, px; 16 px = 1 block)",fontsize=9)
plt.tight_layout(); plt.savefig("flag_model_geometry.png",facecolor="#d8d8d8")
print("ok")
