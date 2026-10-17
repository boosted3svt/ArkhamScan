# Source, rights, and coverage register

This app is unofficial and is not affiliated with Fantasy Flight Games. Arkham
Horror: The Card Game and its card illustrations belong to their respective rights
holders. No permission to redistribute images is asserted here.

| Source | Role | Observed status | Supported use in this project |
| --- | --- | --- | --- |
| [Kamalisk/arkhamdb-json-data](https://github.com/Kamalisk/arkhamdb-json-data) | English card/pack facts and stable source codes | Read-only Git access succeeded; pinned audit commit `6f4760ebe91db212b54473db605771167d86c842`. No explicit repository license found in inspected tree. | User-supplied local converter input. No dataset embedded in APK or source. |
| [zzorba/arkhamdb-json-data](https://github.com/zzorba/arkhamdb-json-data) | Alternative maintained data repository | HEAD resolved to `4ac7e1640dd75862b5e29bb6f9b604dc2e555355`; guessed LICENSE path returned 404. Full content reconciliation not performed. | Candidate for later audit, not authoritative or bundled. |
| [ArkhamDB public API](https://arkhamdb.com/api/public/cards/) | Optional online metadata | Network policy returned proxy 403 in this environment. This does not establish service availability or terms. | Not required by runtime. No credentials requested. |
| Publisher product lists / printed manifests | Authoritative physical contents | Not independently audited here. | Required before a product can be called verified or complete. |
| Catalog illustrations | Visual recognition / manual reference display | Rights, stable URLs, versions, hashes, and offline permission unverified. | Not downloaded or displayed. No placeholder image is described as matched evidence. |
| Google ML Kit bundled Latin OCR | Offline text suggestions | Version 16.0.1; dependency terms apply. No accuracy benchmark on target cards. | In-memory OCR only; INTERNET permission explicitly removed from merged manifest. |

Audit counts are not a certification of release coverage. Source pack `quantity`
fields are deliberately omitted by the converter: they have not been independently
validated as expected physical quantities for every historical retail format.
Source IDs remain distinct; same-title records are never merged into one printing.
Hidden pseudo-cards are excluded. Conflicting duplicate IDs fail conversion.

Runtime catalog replacement does not rewrite historical copies. Each confirmation
retains source, imported-file hash, printing ID, title snapshot, and confirmation
method. `referenceImageId` is null because this alpha performs no image matching;
OCR suggestions do not fabricate image provenance.
