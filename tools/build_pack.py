"""Builds resourcepack/GrandTab-ResourcePack.zip from resourcepack/source and prints its SHA-1.

Run from the project folder:  python3 tools/build_pack.py
The zip is reproducible (fixed timestamps), so the SHA-1 only changes when the files change.
"""
import hashlib, os, zipfile

root = os.path.join("resourcepack", "source")
out = os.path.join("resourcepack", "GrandTab-ResourcePack.zip")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for folder, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            path = os.path.join(folder, name)
            arc = os.path.relpath(path, root).replace(os.sep, "/")
            info = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as f:
                z.writestr(info, f.read())
sha1 = hashlib.sha1(open(out, "rb").read()).hexdigest()
print(out)
print("resource-pack-sha1=" + sha1)
