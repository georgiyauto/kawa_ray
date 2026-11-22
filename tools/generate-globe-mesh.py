"""Build the runtime dot mesh from pinned Natural Earth land polygons.

Development dependency: Pillow. No downloads or GIS packages at runtime.
Data: Natural Earth public domain, natural-earth-vector v5.1.2.
"""
from pathlib import Path
import json,math,struct
from PIL import Image,ImageDraw
root=Path(__file__).resolve().parents[1]
data=json.loads((root/'tools/data/ne_110m_land.geojson').read_text())
mask=Image.new('L',(2880,1440));draw=ImageDraw.Draw(mask)
def xy(lon,lat):return ((lon+180)*8,(90-lat)*8)
for feature in data['features']:
 geometry=feature['geometry'];coordinates=geometry['coordinates']
 polygons=[coordinates] if geometry['type']=='Polygon' else coordinates
 for rings in polygons:
  for i,ring in enumerate(rings):draw.polygon([xy(*point[:2]) for point in ring],fill=255 if i==0 else 0)
def land(lat,lon):return mask.getpixel((min(2879,max(0,int((lon+180)*8))),min(1439,max(0,int((90-lat)*8)))))>0
for lat,lon in [(10,20),(-20,135),(-15,-55),(40,-100),(52,20),(-80,0)]:assert land(lat,lon),(lat,lon)
for lat,lon in [(0,-140),(0,-30),(-40,80)]:assert not land(lat,lon),(lat,lon)
points=[]
for lat in range(-89,90,2):
 count=max(4,round(180*math.cos(math.radians(lat))))
 for j in range(count):
  lon=-180+(j+0.5*(lat%4==1))*360/count
  if land(lat,lon):points.append((lat,lon))
output=root/'crates/kawaray-gui/ui/assets/globe-points.bin'
output.write_bytes(b''.join(struct.pack('<ff',*point) for point in points))
print('Natural Earth mesh:',len(points),'land points;',output.stat().st_size,'bytes')
