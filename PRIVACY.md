# Khmer Dubber – Privacy Policy

_Last updated: 11 November 2026_

Khmer Dubber turns a video and its subtitle file into a video with a Khmer voice. This page explains
what happens to your data when you use the app.

## What stays on your phone

- **Your videos** are read and saved only on your phone. The picture is never uploaded. The sound is
  uploaded only when you dub a video without a subtitle file (see "Automatic subtitles" below).
- **Your subtitle files**, your **logo**, your **lower third** (text, picture or video) and your
  settings are kept only on your phone.
- Finished videos are saved to `Movies/KhmerDubber` and Khmer subtitles to `Download/KhmerDubber`.

## What is sent over the internet, and why

To translate and to make the Khmer voice, the **text of the subtitle lines** is sent, over an
encrypted connection, to:

| Service | What it receives | Why |
|---|---|---|
| Google Translate (`translate.googleapis.com`) | Subtitle text | To translate it into Khmer |
| Microsoft speech service (`speech.platform.bing.com`) | Khmer subtitle text, voice name, speed and pitch | To create the Khmer voice audio |

Only the subtitle text is sent. No video, picture, file name, account, contact, location or device
identifier is sent. These services handle the text under their own privacy policies:
[Google](https://policies.google.com/privacy) and
[Microsoft](https://privacy.microsoft.com/privacystatement).

### Automatic subtitles (only when you choose a video without a subtitle file)

If you start dubbing a video and do not choose a subtitle file for it, the app writes the subtitle
from what is said in the video. To do that it sends the video's **sound** (speech-quality audio, no
picture and no file name) over an encrypted connection to OpenAI's speech-to-text service
(`api.openai.com`), which sends back the spoken lines and their times.

- The audio is sent through the app developer's OpenAI API account. No name, account or device
  identifier of yours is sent with it. OpenAI handles it under its
  [API data policy](https://openai.com/policies/privacy-policy).
- The developer does not receive or keep the audio or the subtitle.
- To keep a video's sound on your phone, choose its own .srt subtitle file. Nothing is uploaded then
  except the subtitle text described above.

## What we do not do

- No account or sign-in.
- No ads, no analytics and no tracking.
- The developer does not run a server and does not receive or store any of your data.

## Permissions

- **Internet**: to translate, to create the voice, and to write automatic subtitles.
- **Notifications** and **foreground service**: to show progress while a video is being dubbed, so it
  can finish while you use other apps.
- **Storage** (Android 9 and older only): to save the finished video.

## Children

Khmer Dubber is not directed at children under 13.

## Changes and contact

If this policy changes, the new version will be posted on this page with a new date.
Questions: open an issue at
<https://github.com/nysovannreach991-prog/khmer-drama-dubber/issues>.

---

## គោលការណ៍ឯកជនភាព (សង្ខេប)

- វីដេអូ ឯកសារអក្សររត់ Logo Lower third និងការកំណត់របស់អ្នក នៅតែលើទូរស័ព្ទរបស់អ្នក ហើយមិនត្រូវបានផ្ញើទៅណាឡើយ។
- បើអ្នកជ្រើសរើសតែវីដេអូ ដោយគ្មានឯកសារអក្សររត់ នោះសំឡេងរបស់វីដេអូ (មិនមែនរូបភាពទេ) ត្រូវបានផ្ញើទៅសេវារបស់ OpenAI ដើម្បីសរសេរអក្សររត់ដោយស្វ័យប្រវត្តិ។ បើអ្នកជ្រើសរើសឯកសារអក្សររត់ដោយខ្លួនឯង សំឡេងមិនត្រូវបានផ្ញើទេ។
- ក្រៅពីនេះ មានតែអត្ថបទនៃអក្សររត់ប៉ុណ្ណោះ ដែលត្រូវបានផ្ញើ (តាមការតភ្ជាប់ដែលបានអ៊ិនគ្រីប) ទៅ Google Translate ដើម្បីបកប្រែ និងទៅសេវាសំឡេងរបស់ Microsoft ដើម្បីបង្កើតសំឡេងខ្មែរ។
- គ្មានគណនី គ្មានការផ្សាយពាណិជ្ជកម្ម គ្មានការតាមដាន ហើយអ្នកបង្កើតកម្មវិធីមិនរក្សាទុកទិន្នន័យរបស់អ្នកទេ។
