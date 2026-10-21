#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
แปลงไฟล์ playlist ฟอร์แมต "provider" (provider/categories/channels)
ให้เป็น JSON ฟอร์แมตของ BIOplayer (groups/stations แบบ Wiseplay)

การใช้งาน:  python3 convert_provider.py <ไฟล์ต้นทาง> <ไฟล์ปลายทาง>
รองรับไฟล์ต้นทางที่ JSON หลวม ๆ (เลขนำหน้า 0, ลืมปิด quote) — ซ่อมให้อัตโนมัติ
(ทำเป็นบรรทัด ๆ แบบเดียวกับ JsonRepair ในแอป เพื่อไม่ไปแตะค่าใน string)
"""
import json
import re
import sys

# บรรทัดที่เป็น "key": 01234... (ค่าเป็นตัวเลข) — ตัดเลข 0 ด้านหน้าออก
NUMBER_VALUE = re.compile(r'^(\s*"[^"]+"?\s*:\s*)0+(\d.*)$')
# บรรทัดที่เริ่มด้วย "key": "value ที่ยังไม่มี quote ปิดท้าย
OPEN_STRING = re.compile(r'^(\s*"[^"]+"?\s*:\s*")(.*)$')
CLOSED_TAIL = re.compile(r'"(\s*,)?\s*$')


def repair(txt: str) -> str:
    """ซ่อม JSON หลวม ๆ ให้ parse ได้ (เหมือน JsonRepair ในแอป)"""
    txt = txt.replace("\r\n", "\n")
    out = []
    for line in txt.split("\n"):
        # 1) "number": 02 -> "number": 2 (เฉพาะบรรทัดที่ค่าเป็นตัวเลข)
        m = NUMBER_VALUE.match(line)
        if m:
            line = m.group(1) + m.group(2)
        # 2) string ลืมปิด quote -> เติม " ให้ท้ายบรรทัด
        m = OPEN_STRING.match(line)
        if m and not CLOSED_TAIL.search(m.group(2)):
            rest = m.group(2)
            if rest.endswith(","):
                rest = rest[:-1] + '",'
            else:
                rest = rest + '",'
            line = m.group(1) + rest
        out.append(line)
    return "\n".join(out)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    txt = open(src, encoding="utf-8").read()
    try:
        d = json.loads(txt)
    except json.JSONDecodeError:
        d = json.loads(repair(txt))
        print("ต้นฉบับมี JSON ที่หลวม ๆ — ซ่อมให้อัตโนมัติแล้ว")

    categories = d.get("categories") or {}
    channels = d.get("channels") or []
    prov = d.get("provider") or {}

    groups = {}   # key = category, เก็บลำดับตามที่เจอครั้งแรก
    skipped_noname = 0
    skipped_nourl = 0
    for c in channels:
        cat = (c.get("category") or "อื่น ๆ").strip() or "อื่น ๆ"
        if cat not in groups:
            meta = categories.get(cat) or {}
            groups[cat] = {
                "name": meta.get("name") or cat,
                "image": meta.get("icon") or "",
                "stations": [],
            }
        name = (c.get("name") or "").strip()
        url = (c.get("url") or "").strip()
        if not url:
            url = (c.get("") or "").strip()  # URL ใส่ผิด key ("")
        if not name:
            skipped_noname += 1
            continue
        if not url:
            skipped_nourl += 1
            continue
        st = {
            "name": name,
            "url": url,
            "image": c.get("icon") or "",
            "referer": c.get("referer") or "",
        }
        ua = c.get("user_agent") or c.get("userAgent") or ""
        if ua:
            st["user_agent"] = ua
        groups[cat]["stations"].append(st)

    out = {
        "name": prov.get("user_message") or d.get("name") or "Playlist",
        "image": "",
        "author": "",
        "groups": list(groups.values()),
    }
    total = sum(len(g["stations"]) for g in groups.values())
    json.dump(out, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"แปลงเสร็จ: {len(groups)} หมวด, {total} ช่อง")
    print(f"ข้าม {skipped_nourl} ช่องที่ไม่มี URL และ {skipped_noname} ช่องที่ไม่มีชื่อ")
    for g in groups.values():
        print(f"  - {g['name']}: {len(g['stations'])} ช่อง")


if __name__ == "__main__":
    main()
