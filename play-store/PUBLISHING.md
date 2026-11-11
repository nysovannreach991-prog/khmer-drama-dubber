# Publishing Khmer Dubber on Google Play

Everything the Play Console asks for, in order, with the answers ready.
Files mentioned here are in this `play-store/` folder unless noted.

## 0. What only you can do

- Create the developer account at <https://play.google.com/console/signup> → **Yourself**.
  Pay the one-time **$25**, verify your **ID** and **phone**, and confirm you have an Android phone
  (Play asks you to install the Play Console app). Verification can take a few days.
- Find **testers** (see step 6) and publish when you're ready. Publishing is your decision.

## 1. Create the app

Play Console → **Create app**

| Field | Answer |
|---|---|
| App name | Khmer Dubber: Khmer voice-over |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free (can't be changed to paid later) |
| Declarations | Tick both (Developer Program Policies, US export laws) |

The package name is set by the first upload: **com.khmerdubber.app** (permanent).

## 2. App content (Policy and programs → App content)

**Privacy policy**
`https://github.com/nysovannreach991-prog/khmer-drama-dubber/blob/main/PRIVACY.md`
(online once this folder and `PRIVACY.md` are pushed to GitHub)

**App access:** All functionality is available without special access.

**Ads:** No, my app does not contain ads.

**Content rating** (questionnaire): Category **All other app types**. Answer **No** to violence,
sexuality, language, controlled substances, gambling, user interaction/sharing, sharing location,
digital purchases, and unrestricted web access. Expected result: Everyone / 3+.

**Target audience and content:** Age groups **18 and over** only. "Could the app unintentionally
appeal to children?" No. (Keeps it outside the Families program and its extra rules.)

**News app:** No. **Government app:** No. **Financial features:** None. **Health:** None.

**Data safety**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | No (nothing is stored) |
| Data types | **Files and docs**, and **Audio → Other audio files** |
| Files and docs → Collected | Yes |
| Files and docs → Shared | Yes (subtitle text goes to Google Translate and Microsoft's speech service) |
| Processed ephemerally? | Yes |
| Required or optional? | Required |
| Purpose (collected and shared) | App functionality |

| Other audio files → Collected | Yes |
| Other audio files → Shared | Yes (when a video is dubbed without a .srt, its sound goes to OpenAI's speech-to-text service to write the subtitle) |
| Other audio files → Processed ephemerally? | No (OpenAI may keep API data for a limited time) |
| Other audio files → Required or optional? | Optional (the user can choose a .srt file instead) |
| Other audio files → Purpose | App functionality |

Nothing else is collected: no location, contacts, IDs, photos/videos (the picture never leaves the
phone), voice recordings, app activity, crash logs or diagnostics.

**Foreground service permissions** → `FOREGROUND_SERVICE_DATA_SYNC` → task type **Data sync**:

> When the user taps "Start dubbing", the app downloads the Khmer voice for each subtitle line from a
> speech server, mixes it into the user's video and saves the finished video. This takes from one to
> several minutes per episode, so it runs as a foreground service with a progress notification while
> the user uses other apps. It stops as soon as the video is saved or the user taps Stop.

Video link: upload `foreground-service-demo.mp4` to YouTube as **Unlisted** and paste the link.
It shows Start dubbing → leaving the app → the progress notification → "Finished".

## 3. Store listing (Grow users → Store presence → Main store listing)

Texts and graphics: see `LISTING.md`. Add the Khmer translation there too.

## 4. App signing (first upload)

So phones that installed the GitHub version can switch to the Play version without uninstalling, give
Play **your existing signing key** instead of letting Google make a new one:
Test and release → **App integrity → App signing → Change app signing key → Export and upload a key
from Java keystore**. Play shows a command using its **PEPK** tool and an encryption key. Ask Claude to
run it with `android-app/khmerdubber.jks` (alias `khmerdubber`); upload the `.zip` it makes.
Never share the `.jks` file or its password with anyone.

## 5. Build the upload file

```bash
./play-bundle.sh
```

This makes `KhmerDubber-play-vX.Y.aab` in the project folder (the Play build: no GitHub self-updater,
targets Android 16 / API 36). Every upload needs a higher version: run `./release.sh X.Y` (or bump
`android-app/version.properties`) first.

## 6. Closed test (needed before going public)

New personal accounts must run a **closed test** with enough testers **opted in for 14 days in a row**
before Play lets you apply for production. Google's help page says **20** testers; many guides say 12.
Check the number your Console shows.

1. Test and release → Testing → **Closed testing** → Create track.
2. Testers: an email list (their Gmail addresses) or a Google Group.
3. Create release → upload the `.aab` → release notes → Review → **Start rollout to Closed testing**.
4. Send testers the opt-in link from the Testers tab. They must opt in **and install** the app, and stay
   opted in for the whole 14 days.
5. After 14 days: Dashboard → **Apply for production** (questions about your test). Review usually
   takes up to 7 days.

## 7. Production

Test and release → **Production** → Create release → same `.aab` (or newer) → Review → Rollout.

## Updating later

- Play version: bump the version, `./play-bundle.sh`, upload to Production.
- GitHub version: `./release.sh X.Y "notes"` as before (sideloaded phones keep updating themselves).
- Keep both on the same version number so neither looks older than the other.
