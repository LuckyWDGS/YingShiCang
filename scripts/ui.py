"""Snapshot UI nodes or activate a tree-derived node, always on the designated emulator."""
import argparse
import os
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET
from adb_target import verified_adb
import sys

sys.stdout.reconfigure(encoding="utf-8")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--tap", help="Exact visible text or resource id")
    p.add_argument("--serial", default="emulator-5580")
    p.add_argument("--save", type=pathlib.Path)
    p.add_argument("--scroll", choices=["up", "down"])
    args = p.parse_args()
    try:
        base = verified_adb(args.serial)
    except ValueError as error:
        p.error(str(error))
    # /dev/tty can return an unrelated/stale hierarchy on LDPlayer. Use a fresh file.
    remote = "/sdcard/ysc-ui.xml"
    subprocess.run(base+["shell","rm","-f",remote],check=True,timeout=10)
    subprocess.run(base+["shell","uiautomator","dump",remote],check=True,timeout=25,stdout=subprocess.DEVNULL)
    raw = subprocess.check_output(base+["exec-out","cat",remote],timeout=15).decode("utf-8")
    if args.save:
        args.save.parent.mkdir(parents=True,exist_ok=True)
        args.save.write_text(raw,encoding="utf-8")
    end = raw.rfind("</hierarchy>")
    if end < 0:
        print(raw)
        raise SystemExit(1)
    tree = ET.fromstring(raw[:end+12])
    if args.scroll:
        for node in tree.iter("node"):
            if node.get("scrollable") != "true":
                continue
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) != 4 or bounds[3] <= bounds[1]:
                continue
            x = (bounds[0] + bounds[2]) // 2
            top = bounds[1] + (bounds[3] - bounds[1]) // 5
            bottom = bounds[3] - (bounds[3] - bounds[1]) // 5
            start, stop = (bottom, top) if args.scroll == "down" else (top, bottom)
            subprocess.run(base+["shell","input","swipe",str(x),str(start),str(x),str(stop),"400"],check=True)
            return
        p.error("No scrollable surface in current UI")
    for n in tree.iter("node"):
        text, rid = n.get("text",""),n.get("resource-id","")
        if text or n.get("clickable")=="true":
            print(repr(text),rid,n.get("bounds"),"FOCUS" if n.get("focused")=="true" else "")
        if args.tap and args.tap in (text,rid):
            numbers=list(map(int,re.findall(r"\d+",n.get("bounds",""))))
            if len(numbers)!=4 or numbers[2]<=numbers[0] or numbers[3]<=numbers[1]:continue
            subprocess.run(base+["shell","input","tap",str((numbers[0]+numbers[2])//2),str((numbers[1]+numbers[3])//2)],check=True)
            return
    if args.tap: p.error("Target not in current UI tree")


if __name__=="__main__":main()
