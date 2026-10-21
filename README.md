# BIOplayer

Android (Kotlin) — เล่นสตรีมจาก playlist แบบ Wiseplay รองรับ JSON ซ้อนหลายชั้น (group ⊃ group ⊃ ... ⊃ station แบบไม่จำกัดชั้น) และ M3U (พร้อม group-logo) — ใช้งานได้ทั้งมือถือและ Android TV ด้วย UI เดียวกัน

## หน้าจอ
1. **Lists** (`MainActivity`) — รายการ playlist ที่บันทึกไว้ (ชื่อ/author/รูป) กด FAB "Add list" เลือก "Add list from URL" หรือ "Add list from file" (ยังไม่ทำ QR / migrate / share — เพิ่มได้ทีหลังถ้าต้องการ)
2. **Group Browser** (`GroupBrowserActivity`) — กด playlist แล้วไล่ลึกลงไปในโครงสร้าง group ซ้อน group ได้ไม่จำกัดชั้น จนถึงชั้นที่มี station แล้วกดเล่นได้เลย
3. **Player** (`PlayerActivity`) — ExoPlayer (Media3) เล่น HLS/mp4 พร้อม custom User-Agent/Referer ต่อช่อง

## รูปแบบ Playlist ที่รองรับ

### JSON แบบซ้อนหลายชั้น (ตรงกับ `playlist_loader.py` ที่ให้มา)
```json
{
  "name": "live sport", "image": "...", "author": "update 25-09-2026 10:13",
  "groups": [
    {
      "name": "UEFA Nations League", "image": "...",
      "groups": [
        {
          "name": "16:00 Georgia vs N.Ireland", "image": "...",
          "stations": [
            { "name": "SIAM-1", "image": "...", "url": "...m3u8", "referer": "...", "userAgent": "..." }
          ]
        }
      ]
    }
  ]
}
```
`groups` และ `stations` ซ้อนกันได้ในโหนดเดียวพร้อมกัน (มีทั้งคู่ก็ได้) และไม่จำกัดความลึก — parser (`JsonPlaylistParser.kt`) เป็น recursive ตรงกับ `_parse_group` ใน python ที่ส่งมา

### M3U (พร้อม group-logo) — จัดกลุ่มได้ชั้นเดียว (ตาม logic เดิม)
```
#EXTINF:-1 tvg-logo="..." group-title="Sport" group-logo="...",Ball66
http://example.com/stream.m3u8
```

`PlaylistParser.kt` (facade) จะลอง parse เป็น JSON ก่อนเสมอ ถ้า parse ไม่ผ่านค่อย fallback เป็น M3U — ตรงกับ order ของ python (`try json → except → m3u`)

## โครงสร้างโค้ดหลัก
- `model/PlaylistTree.kt` — `Group` (recursive: groups[] + stations[]) และ `Station`, ทั้งคู่ `Parcelable` (ส่งข้าม Activity ได้ตรง ๆ ไม่ต้อง serialize ซ้ำ)
- `parser/JsonPlaylistParser.kt`, `parser/M3uParser.kt`, `parser/PlaylistParser.kt`
- `storage/SavedPlaylistsStore.kt` — เก็บแค่ entry เบา ๆ (name/author/image/source) ผ่าน SharedPreferences ไม่ได้ cache เนื้อหาเต็ม ๆ ไว้ (fetch ใหม่ทุกครั้งที่เปิด เหมือน IPTV app ทั่วไป)
- `util/ContentFetcher.kt` — fetch จาก URL (OkHttp) หรือไฟล์ที่เลือกไว้ (content:// URI, ขอ persistable permission ตอนเลือกไฟล์)
- `adapter/SavedPlaylistAdapter.kt`, `adapter/NodeAdapter.kt`

## วิธี Build เป็น APK
มี 2 ทาง:
1. **GitHub Actions (ง่ายสุด ไม่ต้องลงอะไร)** — push โค้ดขึ้น branch `main` ของ repo ที่มี `.github/workflows/build-apk.yml` แล้วไปที่หน้า repo → tab **Actions** → เลือก run ล่าสุด (Build APK) → ดาวน์โหลด artifact **BIOplayer-debug-apk** → แตก zip ได้ `app-debug.apk`
2. **Android Studio** — เปิดโฟลเดอร์โปรเจกต์ → รอ sync Gradle → menu **Build → Build APK(s)** → ได้ไฟล์ที่ `app/build/outputs/apk/debug/app-debug.apk`

## วิธีนำเข้าไฟล์ข้อมูล (playlist)
- **ใช้กับแอปเวอร์ชันปัจจุบันได้เลย:** นำไฟล์ `BIOplayer_TV_TH.json` (แปลงจากไฟล์ provider config แล้ว) ไปวางไว้ในเครื่อง แล้วในแอปกด **Add list → Add list from file** เลือกไฟล์นั้น — ได้ 8 หมวด 314 ช่อง (ฟิลด์ referer / user_agent / ลิงก์แบบ `url|Referer=...` ครบ)
  (หมายเหตุ: ไฟล์นี้อยู่ในรูปแบบ playlist ที่ซอร์สโค้ดเดิมรองรับ แต่ยังไม่ได้ทดสอบนำเข้ากับ APK ที่แนบมา และยังไม่ได้ทดสอบเล่นสตรีมจริง — จำนวน 314 คือจำนวนรายการที่นำเข้าได้ ไม่ใช่การยืนยันว่าเล่นได้ทุกช่อง)
- **ถ้า build แอปใหม่จากโค้ดนี้:** โค้ดส่วนที่เพิ่มรองรับโครงสร้าง provider/categories/channels และมีขั้นตอนลองซ่อมข้อผิดพลาดที่พบในไฟล์ตัวอย่าง แต่ยังไม่ได้ build หรือทดสอบบน Android จริง

## ที่ยังไม่ได้ทำ (MVP)
- Add list จาก QR code / migrate จากเวอร์ชันเก่า / share list — โครง UI Wiseplay มีปุ่มพวกนี้แต่ยังไม่ implement
- ไอคอนแอป/TV banner ยังเป็น placeholder
- ยังไม่มี retry อัตโนมัติเมื่อ URL หมดอายุ (สังเกตจากตัวอย่าง JSON ที่ส่งมา ลิงก์มี `wmsAuthSign` ที่หมดอายุใน 360 นาที — ถ้าต้องการให้แอปแจ้งเตือนหรือ refetch playlist อัตโนมัติเมื่อเล่นไม่ได้ บอกได้ จะเพิ่ม retry/fallback ไปยัง URL สำรอง (สังเกตว่าแต่ละ station มีหลาย mirror ซ้ำกันหลายตัวในตัวอย่าง — เพิ่ม auto-failover ไปตัวถัดไปได้ถ้าต้องการ)

## รองรับเพิ่ม: ฟอร์แมต "provider" (provider/categories/channels)
เวอร์ชันนี้รับไฟล์ config แบบ `{ "provider": {...}, "categories": {...}, "channels": [...] }` ได้โดยตรง (แต่ละ channel มี `category`, `name`, `icon`, `url`, `referer`, `user_agent` ฯลฯ) — `JsonPlaylistParser` จะตรวจว่ามี `channels` อยู่แล้วส่งให้ `ProviderFormatParser` แปลงเป็น Group/Station (1 category = 1 group)

สิ่งที่จัดการให้อัตโนมัติ:
- ช่องที่ใส่ URL ผิด key (เป็น `""` แทน `"url"`) — ใช้ค่าจาก `""` เป็น fallback
- ช่องที่ไม่มี URL เลยจะถูกข้าม
- ไฟล์ JSON หลวม ๆ (เลขนำหน้า 0 เช่น `"number": 02`, ลืมปิด quote ท้ายบรรทัด) — `JsonRepair` จะซ่อมให้ก่อน parse ใหม่รอบสอง (ไฟล์ที่ valid อยู่แล้วไม่ถูกแตะ)

ข้อจำกัดที่ควรรู้:
- ฟิลด์ `drm_type`/`drm_key` ยังไม่ถูกนำไปใช้ — การเล่นช่องที่ต้องใช้การตั้งค่า DRM (widevine/clearkey) ยังไม่รองรับ
- ส่วนอื่นของ provider เช่น ip_whitelist, EPG, vod_library เป็น feature แยก ยังไม่ implement
