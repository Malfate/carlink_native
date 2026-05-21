# Global Codex Context

Before doing any work, read and follow `/Users/krushin/.claude/CLAUDE.md` as my persistent user/project preferences.

## Vehicle Context

- My vehicle is a 2025 GMC Sierra EV.

## Carlinkit Manufacturer Reference Context

- This repo does **not** currently contain the raw manufacturer APKs or a full checked-in decompiled source tree.
- It does contain distilled reverse-engineering notes derived from manufacturer/reference apps and firmware analysis, especially AutoKit v2025.03.19.1126, PhoneMirrorBox r5889, and CPC200-CCPA firmware binaries.
- For future compatibility or newer CarlinKit hardware work, check these docs first:
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/usb_protocol.md`
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/video_protocol.md`
  - `documents/reference/adapter/RE_Documention/02_Protocol_Reference/audio_protocol.md`
  - `documents/reference/adapter/RE_Documention/04_Implementation/host_app_guide.md`
  - `documents/reference/adapter/RE_Documention/05_Security_Analysis/crypto_stack.md`
- Treat these docs as the local source of truth for known AutoKit behavior: init sequence (`0xA0`, `0xF0`, Open, BoxSettings), USB bulk protocol framing, heartbeat behavior, touch packet semantics, audio/video payload formats, resolution tier logic, and OEM quirks.
- For newer hardware such as CarlinKit 5.0 / 2air, do not assume compatibility from brand alone. First verify USB VID/PID, descriptor/interface shape, bulk IN/OUT endpoints, and whether the device responds to the CPC200/AutoKit initialization sequence.
