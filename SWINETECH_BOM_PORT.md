# SwineTech Custom Flutterfire — BOM Port Runbook

Executable process for porting SwineTech's custom Flutterfire changes forward onto a new
Firebase **BOM** release. This is the modern (Pigeon‑26 / pub‑workspace / relative‑path) procedure,
refined during the **4.16.1** port. It supersedes the older steps in the Confluence page
"Custom Flutterfire Package" (swinetech.atlassian.net › PigFlow), which assumed the pre‑workspace,
git‑ref era.

> TL;DR for the next port: branch off the new upstream release → diff the *previous* SwineTech working
> branch against *its* baseline to see our changes → re‑apply them onto the new baseline (adapting for
> drift) → regenerate Pigeon → `dart analyze` → revert repo‑wide format noise → convert pubspecs to
> **relative paths** → verify a standalone `flutter pub get` dedupes `_flutterfire_internals`.

---

## 1. What we customize (the deliverables)

All customization lives in **`cloud_firestore`** + **`_flutterfire_internals`**:

1. **QuerySnapshotChanges feature** — a "true" query snapshot that streams only the *changed* documents
   (`DocumentChange`s + metadata) instead of the whole result set. New public `Query.snapshotChanges()`.
2. **write_batch fixes** — reset `_committed = false;` in the commit `catch` so a failed commit can retry,
   plus `removeFromBatch()` and `getBatchData()` API additions.
3. **exception.dart** — for `not-found` errors whose message contains `NOT_FOUND:`, surface the
   Firestore‑provided reason/document path instead of the generic message.

**Why all the BOM packages get touched (not just cloud_firestore):** `_flutterfire_internals` carries the
exception.dart change, and every firebase package depends on it. For a consumer to get *one* forked copy
of `_flutterfire_internals` (not a pub.dev copy), every fork‑family package must reference its
intra‑family deps from the fork, not pub.dev.

## 2. Branch model

| Role | Name | Notes |
|------|------|-------|
| Pristine upstream baseline | `SwineTech/bom_X.Y.Z-baseline` | exact upstream release commit, untouched |
| Working branch (the build) | `SwineTech/bom_X.Y.Z` | baseline + our customizations |
| Reference (previous port) | `origin/SwineTech/bom_<prev>-a` (or `bom_<prev>`) | the last *committed* customization to copy from |

- The **reference** is the diff `SwineTech/bom_<prev>-baseline` → `SwineTech/bom_<prev>(-a)`. That diff *is*
  the customization set to replicate.
- **`-a` / `-b` suffixes are post‑initial revision markers**, not part of the first cut. Ship `bom_X.Y.Z`
  first; if you must amend after it's consumed, cut `bom_X.Y.Z-a` so pub re‑fetches a fresh ref. With the
  relative‑path pubspecs (below) a revision is cheap: push the fix to a new branch and flip **charlotte's one
  `ref:`** — no fork‑pubspec churn.
- An *aborted/unfinished* port branch is NOT a usable reference — use the last fully committed one.

## 3. Prerequisites

```bash
dart pub global activate melos
dart pub global activate flutter_plugin_tools
brew install clang-format swiftformat
```
Toolchain seen for 4.16.1: flutter 3.41.0, dart 3.11.0, pigeon 26.3.4 (google-java-format NOT needed).

---

## 4. Procedure

### Step 0 — Create the branches
Find the upstream release commit (its `chore(release): publish packages` commit; note the BoM version).
Check it out and create `SwineTech/bom_X.Y.Z` + `SwineTech/bom_X.Y.Z-baseline` (identical at first).
Bootstrap so analysis/codegen work:
```bash
melos bootstrap   # resolves the pub workspace (flutter pub get at the root)
```

### Step 1 — Bring in the NEW feature files (added, not modified) verbatim
These don't exist upstream, so copy them straight from the reference branch:
```bash
git diff --name-status --diff-filter=A <prev-baseline>..<prev-ref> -- packages   # list the added files
git checkout <prev-ref> -- <each added path>
```
For 4.16.1 these were 10 files: 2 Android (`streamhandler/QuerySnapshotChangesStreamHandler.java`,
`streamhandler/QuerySnapshotWrapper.java`), 4 iOS (`FLTQuerySnapshotChangesStreamHandler.m`/`.h`,
`QuerySnapshotWrapper.m`/`.h`), 4 Dart (`query_snapshot_changes.dart`,
`platform_interface_query_snapshot_changes.dart`, `method_channel_query_snapshot_changes.dart`,
`method_channel/utils/method_channel_query_snapshot_changes.dart`).

### Step 2 — Hand‑apply the MODIFIED‑file edits, then rename `Pigeon*` → `Internal*`
For each modified, non‑generated, non‑pubspec file, view `git diff <prev-baseline>..<prev-ref> -- <file>`
and apply the hunk onto the new baseline (adapt where upstream drifted). **Files that are *identical*
between the new baseline and the old baseline can just be `git checkout <prev-ref> -- <file>`** (verify with
`git diff --quiet <prev-baseline>..<new-baseline> -- <file>` first; `query.dart` qualified in 4.16.1).

**Pigeon‑26 rename (critical):** Pigeon ≥ 26 forbids class names starting with `Pigeon`. Upstream renamed
the message types `Pigeon*` → `Internal*`. So our new pigeon class is **`InternalQuerySnapshotChanges`**, and
ported code referencing the 11 message types must use `Internal*`. Rename with a guard that protects
`toPigeon*` method names and `FirestorePigeonFirebaseApp`:
```
(?<![A-Za-z])Pigeon<Type>\b   →   Internal<Type>
```
Merge points worth noting: `exception.dart` (splice the not‑found block into the `if (details != null)`
branch); `method_channel_firestore.dart` already uses `PigeonCodec()` for channels (use it for the new
`querySnapshotChangesChannel`); pigeon input `pigeons/messages.dart` gets the new class + 3 `@async` host
methods (`namedQueryGetChanges`, `queryGetChanges`, `querySnapshotChanges`).

### Step 2b — Native: MIRROR the upstream regular‑query path (do NOT copy the old wrapper approach)
**Biggest lesson of the 4.16.1 port.** The old reference used a `QuerySnapshotWrapper` + a manual
`List`‑decode on the Dart side. Under Pigeon 26 that is fragile — upstream rewrote the *regular* query path
to **emit the Pigeon object directly** through the Pigeon‑aware codec. Mirror that for the changes feature:

- **Android** `QuerySnapshotChangesStreamHandler.java`: `events.success(PigeonParser.toPigeonQuerySnapshotChanges(qs, behavior))`
  (mirror `QuerySnapshotsStreamHandler`). Add `toPigeonQuerySnapshotChanges` to `PigeonParser` (mirror
  `toPigeonQuerySnapshot`, minus `documents`). Add 3 plugin methods mirroring `namedQueryGet`/`queryGet`/`querySnapshot`
  (register `QuerySnapshotChangesStreamHandler` on `METHOD_CHANNEL_NAME + "/queryChanges"`); import the handler class.
- **iOS** `FLTQuerySnapshotChangesStreamHandler.m`: `events([FirestorePigeonParser toPigeonQuerySnapshotChanges:... ])`
  (mirror `FLTQuerySnapshotStreamHandler`). Add `toPigeonQuerySnapshotChanges` to `FirestorePigeonParser` (`.h`+`.m`).
  Add 3 plugin methods mirroring `namedQueryGetApp`/`queryGetApp`/`querySnapshotApp`; add the import + a
  `kFLTFirebaseFirestoreQuerySnapshotChangesEventChannelName = @".../queryChanges"` constant.
- **Dart** `method_channel_query.dart` `snapshotChanges()`: mirror `snapshots()` — call `querySnapshotChanges`,
  listen on `querySnapshotChangesChannel`, then `final result = snapshot as InternalQuerySnapshotChanges;`.

Consequence: the codec `writeQuerySnapshotWrapper` dispatch and the iOS writer wrapper serialization are
**unnecessary and skipped**; the copied `QuerySnapshotWrapper.*` files **and** the `utils/method_channel_query_snapshot_changes.dart` duplicate
are unused in this approach — **delete them** (done in 4.16.1, so the new-file set is effectively 6, not 10).
`android/build.gradle`: **re‑add** the commented `//mavenLocal()` repos + custom Firestore impl lines — the
swap‑in point for a locally‑built custom Firestore AAR (the separate "custom firebase‑android‑sdk" task). Pin to
the BOM's resolved Firestore version + `-a`: for 4.16.1, firebase‑bom `34.15.0` → Firestore `26.4.0` → build the
fork AAR as `26.4.0-a` (its transitive deps `firebase-common 22.0.1` + `play-services-tasks 18.4.0`). Derive the
versions from `firebase_core/android/gradle.properties` (`FirebaseSDKVersion`) → the firebase‑bom POM →
the firestore POM, all under `dl.google.com/dl/android/maven2`. Still skipped/tangential: the
`setLoggingEnabled(false)` codec tweak.

### Step 2c — Verify every `*Changes` artifact matches its CURRENT non‑`*Changes` source (ALL file types)
This is the diligence the Confluence doc demands. Every `*Changes` function/type is derived from a non‑`*Changes`
original (`querySnapshotChanges`←`querySnapshot`, `QuerySnapshotChanges`←`QuerySnapshot`, `toPigeonQuerySnapshotChanges`←`toPigeonQuerySnapshot`, …).
**Upstream may have changed the original between the previous and the new baseline**, so confirm each `*Changes`
artifact carries that change. There are two production methods, verified differently:

- **Mirror‑derived** (Step 2/2b built it by copying the *current* non‑`*Changes` source and swapping the
  type/name): all native Java + ObjC plugin methods, the `*PigeonParser` converters, the stream handlers, and
  Dart `method_channel_query.dart`'s `snapshots`. → Current by construction. Confirm by undoing the swap and
  diffing against the current source; the residual must be empty **except** the *intended* `documents` omission
  in the two `toPigeonQuerySnapshot` converters and cosmetic auto‑formatter line‑wrapping. (Script in §6.)
- **Copied** from the previous `-a` reference (Step 1 `git checkout`): the new Dart files + the checked‑out
  `query.dart`. These are modeled on the *previous* baseline and can be **stale**. → Confirm by base→base diffing
  each MODEL source; if a model drifted, fold the same change into the copied `*Changes` file.

`dart analyze` is a backstop for **Dart only** (a stale `Pigeon*`/type reference fails analysis). **Java/ObjC have
no such backstop**, so the explicit diff is the only guard there — don't skip it.

`*Changes` ↔ source map (verify each):

| `*Changes` artifact | derived from | produced by | verify with |
|---|---|---|---|
| Java `querySnapshotChanges` / `queryGetChanges` / `namedQueryGetChanges` | `querySnapshot` / `queryGet` / `namedQueryGet` | mirror | swap‑diff |
| Java `PigeonParser.toPigeonQuerySnapshotChanges` | `toPigeonQuerySnapshot` | mirror (− documents) | swap‑diff |
| Java `QuerySnapshotChangesStreamHandler` | `QuerySnapshotsStreamHandler` | mirror | swap‑diff |
| iOS `querySnapshotChangesApp` / `queryGetChangesApp` / `namedQueryGetChangesApp` | `…App` counterparts | mirror | swap‑diff |
| iOS `FirestorePigeonParser toPigeonQuerySnapshotChanges` | `toPigeonQuerySnapshot` | mirror (− documents) | swap‑diff |
| iOS `FLTQuerySnapshotChangesStreamHandler` | `FLTQuerySnapshotStreamHandler` | mirror | swap‑diff |
| Dart `method_channel_query.snapshotChanges` | same‑file `snapshots` | mirror | swap‑diff |
| Dart `query.dart snapshotChanges` (abstract + `_Json` + `_WithConverter`) | same‑file `snapshots` | copied (`query.dart` checkout) | base→base drift of `query.dart` |
| Dart `platform_interface_query.snapshotChanges` (abstract) | same‑file `snapshots` | hand‑added | base→base drift of file |
| Dart `query_snapshot_changes.dart` (`QuerySnapshotChanges` / `_Json` / `_WithConverter`) | `query_snapshot.dart` | copied | base→base drift |
| Dart `platform_interface_query_snapshot_changes.dart` (`QuerySnapshotChangesPlatform`) | `platform_interface_query_snapshot.dart` | copied | base→base drift |
| Dart `method_channel_query_snapshot_changes.dart` (`MethodChannelQuerySnapshotChanges`) | `method_channel_query_snapshot.dart` (+ uses `method_channel_document_change.dart`) | copied | base→base drift |
| Pigeon `messages.dart` `InternalQuerySnapshotChanges` | `InternalQuerySnapshot` | hand‑added | structural review |

```bash
# (a) drift check for the COPIED Dart files — did their MODEL sources change between baselines?
for f in cloud_firestore/lib/src/query.dart \
         cloud_firestore/lib/src/query_snapshot.dart \
         cloud_firestore_platform_interface/lib/src/platform_interface/platform_interface_query.dart \
         cloud_firestore_platform_interface/lib/src/platform_interface/platform_interface_query_snapshot.dart \
         cloud_firestore_platform_interface/lib/src/method_channel/method_channel_query_snapshot.dart \
         cloud_firestore_platform_interface/lib/src/method_channel/method_channel_document_change.dart; do
  echo "== $f =="; git --no-pager diff <prev-baseline>..<new-baseline> -- "packages/cloud_firestore/$f"
done
# Any non-empty diff that ISN'T just the Pigeon*->Internal* rename must be folded into the copied *Changes file.
# (b) mirror-faithfulness for the mirror-derived functions: use the verify_mirror snippet in §6.
```
In 4.16.1, only `method_channel_query_snapshot.dart` + `method_channel_document_change.dart` drifted, and both
only by the `Pigeon*`→`Internal*` rename — already reflected (and `dart analyze` would fail on a stale ref).

### Step 3 — `generate_pigeon.sh`: keep the UPSTREAM script; verify + patch (don't port old script edits)
`pigeons/generate_pigeon.sh` is an **upstream** file and the current one already does all custom‑codec
reparenting (Java/iOS/Dart/Windows) + the Windows enum‑collision fix. The old reference's edits were
path/format adaptations already absorbed upstream (and the `newIndex → index` patch was an *upstream*
transform that upstream later dropped — do **not** re‑add it). The feature needs **no new script logic**.

### Step 4 — Regenerate Pigeon, then verify (iterative)
```bash
cd packages/cloud_firestore/cloud_firestore_platform_interface/pigeons && bash generate_pigeon.sh
```
The seds are brittle and **silently no‑op** if Pigeon's output shifts. After running, verify:
- `InternalQuerySnapshotChanges` present in all targets: `messages.pigeon.dart`,
  `GeneratedAndroidFirebaseFirestore.java`, iOS `FirestoreMessages.g.m/.h`, windows `messages.g.cpp/.h`.
- Codec transforms landed (no no‑op): Dart `class PigeonCodec extends FirestoreMessageCodec`; Java
  `public static class PigeonCodec extends FlutterFirebaseFirestoreMessageCodec`; iOS reader/writer reparented;
  Windows `FirebaseFirestoreHostApiCodecSerializer`.
- `DocumentChange` `newIndex` round‑trips (only add a `newIndex→index` patch if a real mismatch appears).
If a transform didn't match the new generated output, fix that sed and re‑run. Generated files are NOT hand‑ported.

### Step 5 — `dart analyze` gate (workspace still intact)
```bash
dart analyze packages/_flutterfire_internals
dart analyze packages/cloud_firestore/cloud_firestore_platform_interface
dart analyze packages/cloud_firestore/cloud_firestore
```
Must be clean. **Do this BEFORE converting pubspecs** — once deps point to paths, local analysis resolves
siblings from the converted tree, not the workspace.

### Step 6 — Revert the spurious repo‑wide formatting
`generate_pigeon.sh` runs `melos format-ci` (swiftformat + flutter_plugin_tools) across the whole repo,
reformatting unrelated packages. Revert everything that is NOT in our feature/generated set:
```bash
git diff --name-only <prev-baseline> <prev-ref> | grep -v pubspec.yaml | sort -u > /tmp/legit.txt
git status --short | grep -vE '^\?\?|^A ' | awk '{print $NF}' | sort -u > /tmp/changed.txt
comm -23 /tmp/changed.txt /tmp/legit.txt | xargs git checkout HEAD --
```
Sanity‑check the revert list first (group by package; confirm no feature/generated file is in it).

### Step 7 — BOM pubspec conversion → RELATIVE PATHS
**Approach (verified by spike):** relative‑`path:` deps inside a git dependency resolve within the *single*
git checkout the consumer pulls; when every top‑level fork dep points at the same repo+ref, all the shared
siblings (esp. `_flutterfire_internals`) dedupe to one copy. This is better than git‑url‑per‑dep because the
fork pubspecs carry **no `ref:`** — refs live only in charlotte, so version bumps never re‑touch them.

For each fork‑family package: convert every intra‑fork dependency from a hosted constraint to relative
`path:`, and strip `resolution: workspace`. Then remove the whole family (incl. examples) from the root
`pubspec.yaml` `workspace:` list. **Also strip `resolution: workspace` from the family's `example/`
pubspecs** (else a standalone `pub get` errors on the orphaned workspace marker). Leave external deps
(meta, collection, flutter, plugin_platform_interface, web, …) alone. Versions stay unchanged for an initial cut.

Fork‑family set (update per release): `_flutterfire_internals`; and the main/`_platform_interface`/`_web`
packages of: cloud_firestore, firebase_core, firebase_analytics, firebase_app_check,
firebase_app_installations, firebase_auth, firebase_crashlytics (no web), firebase_messaging, firebase_performance.

Use a script (see `scripts/swinetech/convert_pubspecs.py` if saved, or the snippet in §6).

### Step 8 — Verify consumption (no push needed)
```bash
cd packages/cloud_firestore/cloud_firestore && flutter pub get   # standalone, via relative paths
# inspect .dart_tool/package_config.json: _flutterfire_internals must appear exactly once,
# and fork packages must resolve to local relative paths.
```
The true end‑to‑end check is charlotte `flutter pub get` against the pushed branch (requires a push).

### Step 9 — charlotte (separate repo, follow‑up)
Point charlotte's `ref:` for each firebase dep at `SwineTech/bom_X.Y.Z`. Requires this branch pushed first.
charlotte keeps a commented local `path:` line per dep for dev (uncomment to develop against a local checkout).

---

## 5. Known gotchas / lessons

- **Pigeon‑26 `Pigeon`→`Internal` rename** is mandatory; new class is `InternalQuerySnapshotChanges`.
- **Mirror the upstream direct‑emit pattern**, not the old wrapper + manual‑decode (Step 2b).
- **`generate_pigeon.sh` seds are brittle** → verify they landed (Step 4); don't re‑add the dropped `newIndex→index`.
- **Relative‑path pubspecs dedupe** and kill ref‑churn; git‑url‑per‑dep is the fallback only if dedup ever fails.
- **Pipelines namespace collision (consumer side):** cloud_firestore 4.16.x exports public top‑level names via
  `part` — notably `enum Type` (also `Field`, `Constant`, `Expression`, `Count`, `Sum`, `Ordering`, …). These
  shadow `dart:core` `Type` / app names in any consumer library that imports cloud_firestore and uses the bare
  identifier. The fork can't fix it (parts can't be selectively hidden). Consumer fix:
  `import 'package:cloud_firestore/cloud_firestore.dart' hide Type;` on the affected library. In charlotte the
  only hit was `lib/services/database.dart`.
- **Native (Java/ObjC/C++) is NOT compile‑verified** by `dart analyze` — only Dart + pub resolution. Optionally
  `flutter build apk` / `flutter build ios --no-codesign` on the cloud_firestore example.
- **"Publishable package can't have path/git dependencies"** analyze warnings on converted pubspecs are
  cosmetic for git/path consumption (pub only enforces on `pub publish`, which the fork never does).

## 6. Reusable scripts

**Pubspec conversion (relative paths).** Update `BOM` to the release's fork‑family package names, then:
```python
# convert_pubspecs.py — run from the flutterfire repo root
import os, re
ROOT=os.getcwd(); PKGS=os.path.join(ROOT,"packages")
BOM={ "_flutterfire_internals",
 "cloud_firestore","cloud_firestore_platform_interface","cloud_firestore_web",
 "firebase_core","firebase_core_platform_interface","firebase_core_web",
 "firebase_analytics","firebase_analytics_platform_interface","firebase_analytics_web",
 "firebase_app_check","firebase_app_check_platform_interface","firebase_app_check_web",
 "firebase_app_installations","firebase_app_installations_platform_interface","firebase_app_installations_web",
 "firebase_auth","firebase_auth_platform_interface","firebase_auth_web",
 "firebase_crashlytics","firebase_crashlytics_platform_interface",
 "firebase_messaging","firebase_messaging_platform_interface","firebase_messaging_web",
 "firebase_performance","firebase_performance_platform_interface","firebase_performance_web" }
name_to_dir={}
for dp,_,fs in os.walk(PKGS):
    if any(p in ("example","pipeline_example",".dart_tool","build","test") for p in dp.split(os.sep)): continue
    if "pubspec.yaml" in fs:
        m=re.search(r'^name:\s*(\S+)\s*$', open(os.path.join(dp,"pubspec.yaml")).read(4000), re.M)
        if m and m.group(1) in BOM: name_to_dir[m.group(1)]=dp
for name,d in name_to_dir.items():
    p=os.path.join(d,"pubspec.yaml"); lines=open(p).read().split("\n"); out=[]; i=0; in_deps=False
    while i<len(lines):
        l=lines[i]
        if re.match(r'^[A-Za-z_]',l): in_deps=l.split(":")[0] in ("dependencies","dev_dependencies")
        if re.match(r'^resolution:\s*workspace\s*$',l): i+=1; continue
        m=re.match(r'^(\s{2})([A-Za-z0-9_]+):(.*)$',l)
        if in_deps and m and m.group(2) in BOM and m.group(2)!=name:
            dep=m.group(2); rel=os.path.relpath(name_to_dir[dep],d); j=i+1
            while j<len(lines) and re.match(r'^\s{4,}',lines[j]): j+=1
            out+= [f"  {dep}:", f"    path: {rel}"]; i=j; continue
        out.append(l); i+=1
    open(p,"w").write("\n".join(out))
# also strip 'resolution: workspace' from each family example/, and remove the family dirs from
# the root pubspec.yaml 'workspace:' list (see git history of this branch for the exact edit).
```

**Verify relative‑path dedup before converting the real tree (optional spike):** build a throwaway git repo
mirroring `packages/<group>/<pkg>` with two top‑level packages whose chains both reach `_flutterfire_internals`
via `path:`, then a consumer that git‑pulls both via a `file://` url at one ref; `dart pub get` and confirm
`_flutterfire_internals` appears once in `package_config.json`.

**`*Changes` mirror‑faithfulness (Step 2c).** Confirms each mirror‑derived `*Changes` function is byte‑identical
to its current non‑`*Changes` source after undoing the name/type swap (comments + whitespace ignored). Empty
residual = faithful; the two `toPigeonQuerySnapshot` converters legitimately differ by the `documents` omission.
```python
# verify_mirror.py — run from the flutterfire repo root
import re, difflib, os
REPO=os.getcwd()
def cur(p): return open(os.path.join(REPO,p),errors='replace').read()
def extract(content, sig_re):              # brace-match a Java/ObjC/Dart function from its signature line
    lines=content.split('\n')
    for i,l in enumerate(lines):
        if re.search(sig_re,l):
            buf=[];d=0;started=False
            for j in range(i,len(lines)):
                buf.append(lines[j])
                for ch in lines[j]:
                    if ch=='{':d+=1;started=True
                    elif ch=='}':d-=1
                if started and d==0: return '\n'.join(buf)
    return None
def code(t):                               # strip comments + blank lines, collapse whitespace
    out=[]
    for x in (t or '').split('\n'):
        s=re.sub(r'\s+',' ',x.strip())
        if s and not s.startswith(('//','*','/*','///')): out.append(s)
    return out
def check(changes, nonchanges, repls, label, intended=''):
    norm=changes
    for x,y in repls: norm=norm.replace(x,y)
    diff=[d for d in difflib.unified_diff(code(nonchanges),code(norm),lineterm='',n=0)
          if d[0] in '+-' and not d.startswith(('+++','---'))]
    print(f'{label}: {"IDENTICAL" if not diff else str(len(diff))+" diff line(s)"}'+(f'  (intended: {intended})' if diff and intended else ''))
    for d in diff: print('   ',d)
PJ='packages/cloud_firestore/cloud_firestore/android/src/main/java/io/flutter/plugins/firebase/firestore/FlutterFirebaseFirestorePlugin.java'
# example pair (repeat for queryGet/namedQueryGet, the iOS *App methods, the parsers, and the stream handlers):
pj=cur(PJ)
check(extract(pj,r'void querySnapshotChanges\('), extract(pj,r'void querySnapshot\('),
      [('querySnapshotChanges','querySnapshot'),('QuerySnapshotChangesStreamHandler','QuerySnapshotsStreamHandler'),('"/queryChanges"','"/query"')],
      'Java plugin querySnapshot')
```
For a full run, repeat `check(...)` for every row of the Step 2c table: Java plugin ×3, `PigeonParser`, the
Java + iOS stream handlers, the iOS plugin ×3, `FirestorePigeonParser`, and Dart `method_channel_query`
(the iOS `*App` methods anchor on selectors like `r'querySnapshotChangesApp:'`; the parsers' `documents`
omission shows up as the only intended residual).

## 7. Per‑port checklist
- [ ] Branches created from the upstream release commit; `melos bootstrap` OK.
- [ ] New feature files copied; modified files re‑applied; `Pigeon*`→`Internal*` rename done.
- [ ] Native mirrors the upstream direct‑emit path (Step 2b).
- [ ] Every `*Changes` artifact verified consistent with its CURRENT non‑`*Changes` source — Java, ObjC **and** Dart (Step 2c).
- [ ] Pigeon regenerated; `InternalQuerySnapshotChanges` + codec transforms verified.
- [ ] `dart analyze` clean on the 3 firestore packages (workspace intact).
- [ ] Spurious repo‑wide formatting reverted.
- [ ] Pubspecs converted to relative paths; `resolution: workspace` stripped (packages + examples); family removed from root workspace.
- [ ] Standalone `flutter pub get` dedupes `_flutterfire_internals` to one copy.
- [ ] (Follow‑ups) native `flutter build` check; push branch; charlotte `ref:` update; `hide Type` if Pipelines collisions surface.
