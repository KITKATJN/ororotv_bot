# Nice to have

Ideas for improving the bot. Tick a box when it's done, move items between sections, add your own anywhere.
Rough effort: 🟢 small (an hour or two) · 🟡 medium (a day) · 🔴 large (several days).

## Your ideas

- [ ] 

## Learning experience

- [ ] 🟢 **Daily reminder** – if a user hasn't practised by a chosen time, send "5 words to keep your 🔥 3-day streak?"; `/remind 19:00`, `/remind off`.
- [ ] 🟢 **"I was right" button** under a ❌ answer – for typos or synonyms the checker missed; fixes the stats and remembers the accepted answer for next time.
- [ ] 🟢 **Mark as learned / hide word** – inline buttons to stop asking a word you know well (or never want to learn).
- [ ] 🟡 **Multiple-choice mode** – 4 inline buttons instead of typing; faster on a phone, good for warm-up.
- [ ] 🟡 **Reverse mode** – show the translation, the user types the English word (harder, trains active vocabulary).
- [ ] 🟡 **Cloze mode** – show the subtitle line with the word blanked out: "You're ______." → "exhausting".
- [ ] 🟡 **Real spaced repetition** (Leitner boxes or SM-2) – each word gets a "next due" date; replaces the current right/wrong weighting (the `words.last_asked` column is already stored for this).
- [ ] 🟡 **Smarter answer checking with Claude** – when the simple check says ❌, ask Claude whether the answer is an acceptable translation in that context.
- [ ] 🟡 **Daily goal & weekly summary** – "10 words a day" with progress, and a Sunday message: words practised, accuracy, best streak, hardest words.
- [ ] 🟡 **Show where the word came from** – the API gives `filmable_id`/`time`; look up the show/episode name (`/api/v2/episodes/:id`) and show "from *Friends* S02E05".
- [ ] 🔴 **Pronunciation** – send a short voice message with the word (TTS), or let users answer by voice.

## Words & sync with ororo.tv

- [ ] 🟢 **Refresh reminder** – the bot can't refresh words on its own (the password stays on the phone), so remind users weekly to tap **Words**.
- [ ] 🟡 **Sync learning status back to ororo.tv** – the site has statuses (well known / unsteady / poorly understood); check whether the API can set them.
- [ ] 🟢 **`/export`** – send the user (or admin) a CSV of their words and stats.

## Monitoring & operations

- [ ] 🟢 **`/health` endpoint** on the Mini App server, so an uptime monitor (e.g. UptimeRobot) can alert when the bot is down.
- [ ] 🟢 **Admin notifications** – message the admins on errors, a new user, or a user hitting a limit.
- [ ] 🟢 **Daily database backup** – `VACUUM INTO 'backups/bot-YYYY-MM-DD.db'`, keep the last 14.
- [ ] 🟡 **Run on a server** – a small VPS with a fixed HTTPS domain (or a named Cloudflare tunnel) instead of a quick tunnel whose address changes every restart; package with a Dockerfile.
- [ ] 🟡 **Metrics** – Prometheus/Grafana counters: answers per minute, imports, translation errors, Telegram API errors.
- [ ] 🔴 **Webhooks instead of long polling** – only needed with many users.

## Protection & privacy

- [ ] 🟢 **Flood limit** – max N messages per user per minute.
- [ ] 🟢 **Claude spending cap** – stop using Claude (fall back to MyMemory) after N words per day across all users.
- [ ] 🟢 **`/forget`** – delete everything stored about a user (words, answers, imports, user row).
- [ ] 🟢 **Privacy note in `/help`** – what is stored, what isn't (no passwords), how to delete it.
- [ ] 🟢 **Ban list** – admins can block a Telegram ID.

## Code quality

- [ ] 🟡 **Unit tests** (JUnit) for `AnswerChecker`, `DictionaryParser`, `TelegramInitData`, `Database` – the scratch smoke tests used during development can be turned into these.
- [ ] 🟢 **Schema versioning** – store a version in `PRAGMA user_version` and apply `ALTER TABLE` steps on start, so new columns can be added safely.
- [ ] 🟢 **Foreign keys** – declare the links between tables and turn on `PRAGMA foreign_keys=ON`.
- [ ] 🟢 **Config file** – one `bot.properties` instead of many `-D` options.
- [ ] 🟡 **Localized bot texts** – Russian / English interface, chosen from the user's Telegram language.
