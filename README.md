# Etesian Glance

One screen, like Google News: a search bar on top that finds everything on the phone, the way Sesame does, and the
news under it.

## Search

Type a few letters and Glance looks everywhere at once, best matches first:

| Section | What | Needs |
|---|---|---|
| Applications | every app, in every profile | — |
| Shortcuts | what apps publish: a chat with someone, a new event… `wa ma` finds Maria in WhatsApp | the platform key (Etesian) |
| Contacts | by name, nickname or number | Contacts |
| Messages | text messages, and every message in chat notifications (WhatsApp, Signal…) | SMS, notification access |
| Notifications | all notifications of the last 30 days, kept after they are dismissed | notification access |
| Settings | the Settings pages people look for (Wi-Fi, Battery, Developer options…) | — |
| Files | by name | all files access |
| News | today's stories and the Feeds app's posts | — |
| Web | the query in the chosen search engine (DuckDuckGo by default); suggestions are opt-in | — |

Matching is Sesame's: the start of the name, the start of any word, initials (`gm` → Google Maps), or several
words each starting a word (`ma ka` → Maria Karra); letters in order only as a last resort. Enter opens the first
result. Nothing typed leaves the phone unless web suggestions are switched on.

## News

- **Google News** without the app or the page: its RSS feeds (Top stories and the topics picked in Settings) for the
  phone's language and country. No ads, no tracking scripts.
- **Your feeds**: the Etesian Feeds app's subscriptions, mixed in.
- **Picked on the phone** (`news/Ranker.kt`): articles about the same thing become one story; a story ranks by how
  fresh it is (half-life 8 hours), how many outlets cover it, whether it is one of your feeds, and what you read
  (topics, outlets and words you open count more, "Fewer like this" counts less, fading over two weeks). Top stories
  are Google's picks; For you never lets one outlet or topic take over.

The choice of stories sits behind `Selection`: Cloud Companion's agents can take it over later without changing the
app.

## With the Etesian launcher

Signed with the ROM's platform key, Glance shares a signature permission with the launcher:

- **News view**: Settings → Home screen views → News puts the news left of the other views.
- **Search everything**: the Applications search shows Glance's results under the apps.

The launcher reads `GlanceProvider` (`news` query, `search` call) and opens results through Glance's own `Open`
activity, the only one able to start app shortcuts, fire notifications' own actions and lend its file access.

## Building

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug builds are signed with `~/android/etesian/keys/platform.p12` when it exists (the ROM's platform key, AOSP's
test key on test-keys builds), so they update the copy in the ROM. The ROM takes the release APK through
`vendor/etesian/glance/build-glance.sh`.

## Licence

Etesian, founded by Kyriakos Bompotis. Licensed under the GNU Affero General Public License, version 3 only (AGPL-3.0-only, [LICENSE](LICENSE)), with additional terms under its section 7 ([ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md)): the attribution "Etesian, founded by Kyriakos Bompotis" must be kept, also where a work based on it shows legal notices; no rights are granted to the Etesian names and logos ([TRADEMARKS.md](TRADEMARKS.md)); the Phorepoc project has an exception. Commercial licences are available from the founder. Third-party parts keep their own licences ([NOTICE](NOTICE)).
