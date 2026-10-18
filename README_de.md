[🇨🇳 中文增强版](README.md) | [🇺🇸 English](README_en.md) | [🇹🇷 Türkçe](README_tr.md) | [🇷🇺 Русский](README_ru.md) | [🇩🇪 Deutsch](README_de.md) | [🇨🇳 中文](README_zh.md)

---

# 🌍 NPC & Items Translator für Hypixel Skyblock & Mehr

Die Sprachbarriere sollte kein Hindernis mehr sein, um Ihre Lieblings-Minecraft-Server zu genießen. **NPC & Items Translator** ist ein vollständig anpassbarer, intelligenter clientseitiger Fabric-Mod, der NPC-Dialoge, Item-Beschreibungen und GUI-Tooltips nahtlos mit KI-Genauigkeit, schwebenden Sprechblasen und realistischer Sprachausgabe (TTS) in Ihre bevorzugte Sprache übersetzt!

Speziell für anspruchsvolle RPG/MMO-Spielmodi wie **Hypixel SkyBlock** entwickelt, verarbeitet dieser Mod komplexe Farbcodes, dynamische Texte (wie Live-Bazaar-Preise), interaktive Chatnachrichten und die UI-Lokalisierung für 17 Sprachen perfekt.

---

## ✨ Hauptfunktionen

### 🗨️ Natürliche Sprechblasen (Über dem Kopf)
Lesen Sie Dialoge genau dort, wo Sie hinsehen! Übersetzte Nachrichten erscheinen direkt über den Köpfen der Charaktere in der 3D-Welt:
- **Gestapelte Dialoge (Comic-/RPG-Stil):** Wenn ein NPC mehrere Sätze hintereinander sagt, verschwinden vorherige Sätze nicht! Ältere Sprechblasen gleiten sanft nach oben in einen vertikalen Stapel, sodass Sie alles in chronologischer Reihenfolge lesen können.
- **Dynamische Lesedauer:** Die Anzeigedauer berechnet sich dynamisch anhand der Textlänge (`4s + 65ms pro Zeichen`). Nach Abschluss der Übersetzung wird zusätzliche Lesezeit gewährt, damit kein Text vorzeitig verschwindet.
- **Modernes abgerundetes Design:** Elegante Sprechblasen mit abgerundeten Ecken, schieferblauem Hintergrund, himmelblauer Umrandung, bernsteingoldenem `[NPC] Name`-Titel und einem Richtungspfeil zum Charakter.
- **Vollständige Konfiguration:** Ein-/Ausschaltbar, Basisdauer (3s - 15s), Nur-NPC-Filter und maximale Sichtweite (6m - 32m) im Einstellungsmenü anpassbar.

### 💬 Interaktive Chat-Übersetzung
Haben Sie es satt, Texte in Ihren Browser zu kopieren? Eine saubere Schaltfläche **[Translate]** (Übersetzen) erscheint automatisch neben Chatnachrichten.  
Klicken Sie darauf, um Dialoge sofort in Ihre Sprache zu übersetzen!

- **Anpassbare Schaltflächenposition:** Platzieren Sie die Schaltfläche am **Anfang** (Standard) oder **Ende** der Chat-Nachricht oder blenden Sie sie ganz aus.
- **Automatische Übersetzung:** Übersetzt alle eingehenden Chatnachrichten automatisch ohne Klick.
- **Nur NPCs übersetzen:** Übersetzt nur NPC-Dialoge, während der normale Spielerchat unberührt bleibt.
- **Sicher & Reversibel:** Klicken Sie jederzeit auf das Sprach-Tag (`[DE]`, `[TR]` usw.), um den Originaltext wiederherzustellen. Alle interaktiven Klick-Ereignisse (*"Klicken, um Auktionshaus zu öffnen"* usw.) bleiben vollständig erhalten!

### 🗣️ Integrierte Sprachausgabe (TTS - Text-to-Speech)
Hören Sie NPC-Dialoge laut in Ihrer Sprache!
- **Natürliche Sprachausgabe:** Liest übersetzte NPC-Nachrichten mithilfe der hochwertigen Google TTS-Engine automatisch vor.
- **Vollständige Anpassung:** Passen Sie **Sprechgeschwindigkeit** (0.25x - 2.00x) und **Tonhöhe / Stimmlage** (0.60x - 1.40x) direkt im Menü an.
- **Plattformübergreifend & Sicher (Kein PowerShell):**
  - **Windows:** 100% nativer Windows Multimedia (`winmm.dll`) In-Memory-Aufruf. Kein PowerShell, keine externen Skripte und keine Antivirus-Fehlalarme.
  - **macOS (MacBook / iMac):** Nativer Apple CoreAudio (`/usr/bin/afplay`) Player mit Echtzeit-Geschwindigkeitssteuerung (`-r`). Funktioniert ohne zusätzliche Installationen.
  - **Linux:** Automatische native Audioweiterleitung (`ffplay`, `mpv`, `play`).
- **Intelligente Warteschlange:** Schnelle Dialogfolgen werden flüssig, nacheinander und ohne Überlappungen vorgelesen.

### 📦 Item-Tooltip-Übersetzung per Tastendruck
Fahren Sie mit der Maus über ein beliebiges Item und drücken Sie benutzerdefinierte Tasten, um die Item-Beschreibung mit Ihrer bevorzugten Übersetzungs-Engine zu übersetzen:
- **`G`** — Übersetzen via **Google Translate** (Kostenlos, Sofort)
- **`X`** — Übersetzen via **Gemini AI** (Intelligent, Hohe Qualität)
- **`C`** — Übersetzen via **Groq AI** (Ultraschnelles LLM)
- **`[Belegbar]`** — Übersetzen via **Mistral API** *(In Steuerung belegbar)*
- **`[Belegbar]`** — Übersetzen via **OpenRouter API** *(In Steuerung belegbar)*
- **`V`** — Übersetzung auf Original zurücksetzen

*Hinweis: Wenn Sie dieselbe Übersetzungstaste zweimal drücken, wird die Übersetzung zurückgesetzt.*

### 🎨 Makellose Farberhaltung
Im Gegensatz zu einfachen Übersetzern, die Minecraft-Formatierungen beschädigen, extrahiert dieser Mod `§`-Farbcodes, übersetzt den reinen Text und stellt die Originalfarben wieder her.  
Ihre **Epischen (Epic)** Items bleiben lila, Ihre **Legendären (Legendary)** Items bleiben orange!

### 🛡️ Intelligente Fehlerbehandlung & Automatischer Modell-Wechsel
- Wenn ein API-Schlüssel fehlt oder ungültig ist, wird eine klare Warnung direkt im Item-Tooltip oder im Chat angezeigt.
- **Automatischer Fallback bei Limit-Überschreitung:** Wenn ein Modell auf Ratenbegrenzungen (429) stößt, schlägt die Übersetzung nicht fehl; der Mod wechselt im Hintergrund automatisch zum **nächsten verfügbaren Modell** desselben Anbieters!

---

## 🌐 17 Unterstützte Sprachen (UI & Übersetzung)

Der Mod bietet vollständige Lokalisierung der Benutzeroberfläche und Übersetzungsunterstützung für **17 Hauptsprachen**:

| Sprache | Code | Name in Landessprache |
| :--- | :--- | :--- |
| 🇬🇧 Englisch | `en_us` | English |
| 🇹🇷 Türkisch | `tr_tr` | Türkçe |
| 🇨🇳 Chinesisch (Vereinfacht) | `zh_cn` | 简体中文 |
| 🇪🇸 Spanisch | `es_es` | Español |
| 🇮🇳 Hindi | `hi_in` | हिन्दी |
| 🇸🇦 Arabisch | `ar_sa` | العربية |
| 🇫🇷 Französisch | `fr_fr` | Français |
| 🇷🇺 Russisch | `ru_ru` | Русский |
| 🇧🇷 Portugiesisch (Brasilien) | `pt_br` | Português (Brasil) |
| 🇮🇩 Indonesisch | `id_id` | Bahasa Indonesia |
| 🇩🇪 Deutsch | `de_de` | Deutsch |
| 🇯🇵 Japanisch | `ja_jp` | 日本語 |
| 🇰🇷 Koreanisch | `ko_kr` | 한국어 |
| 🇻🇳 Vietnamesisch | `vi_vn` | Tiếng Việt |
| 🇮🇹 Italienisch | `it_it` | Italiano |
| 🇵🇱 Polnisch | `pl_pl` | Polski |
| 🇺🇦 Ukrainisch | `uk_ua` | Українська |

---

## ⚙️ Übersetzungs-Engines

Wählen Sie Ihre bevorzugte Engine im Konfigurationsmenü (`/translate`, ModMenu oder Taste **Z**).

| Engine | Beschreibung |
|---|---|
| 🤖 **Gemini AI** | Googles neueste Gemini-Modelle (Gemini 2.5 Flash usw.). Kostenloser API-Schlüssel auf [Google AI Studio](https://aistudio.google.com). |
| ⚡ **Groq AI** | Extrem schnelle LLMs (Llama 3.3 70B, Qwen3) für optimales Minecraft-Kontextverständnis. Kostenloser API-Schlüssel auf [console.groq.com](https://console.groq.com). |
| 🌪️ **Mistral AI** | Leistungsstarke Modelle von Mistral (Mistral Large usw.). Kostenloser API-Schlüssel auf [console.mistral.ai](https://console.mistral.ai). |
| 🌍 **OpenRouter AI** | Zugriff auf Hunderte Modelle (Claude, Llama, Qwen, DeepSeek usw.) über eine einzige API. Schlüssel erhältlich auf [openrouter.ai](https://openrouter.ai). |
| 🌐 **Google Translate** | Kein API-Schlüssel erforderlich! Unbegrenzt, schnell und völlig kostenlos. |

---

## 🔧 Konfiguration & Befehle

- **Menü im Spiel:** Befehl `/translate`, Taste **`Z`** oder `ESC → Mods → NPC & Items Translator`
- **Dynamische Sprache:** Automatisch in Ihre Minecraft-Sprache übersetzen oder eine feste Zielsprache wählen.
- **Tastenbelegungen:** `Optionen → Steuerung → Tastenbelegung → NPC & Items Translator`
- **Wörterbuch zurücksetzen:** `/translate DeleteDict` oder über das Menü im Spiel.

---

## 📦 Kompatibilität & Voraussetzungen

- **Mod-Loader:** Fabric
- **Unterstützte Minecraft-Versionen:** `1.21.11`, `26.1`, `26.2`, `26.3`
- **Benötigte Mods:** 
  - [Fabric API](https://modrinth.com/mod/fabric-api)
  - [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
  - [ModMenu](https://modrinth.com/mod/modmenu)
- **Seite:** AUSSCHLIESSLICH clientseitig — 100% sicher auf Multiplayer-Servern wie Hypixel!

---

## 📄 Lizenz

MIT-Lizenz
