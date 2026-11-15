# Privacy matrix (NF-07, NF-09)

What each feature needs, where data lives, and what (if anything) leaves the phone. Used for the Play Data Safety form.

| Feature | Data used | Stored where | Leaves the phone? | Permission |
| --- | --- | --- | --- | --- |
| Transactions, budgets, everything in a book | Amounts, payees, notes, categories | Encrypted database on the phone (SQLCipher, key in Keystore) | No, unless sync is on | none |
| Attachments (receipts) | Images, PDFs, OCR text | App-private files on the phone | No (attachment sync not built) | none (system picker) |
| Receipt scan | Photo | Cache, then app-private file | No — ML Kit runs on the phone | camera via system app |
| Voice add | Speech | Not stored; text only | Platform recogniser (offline preferred) | none (system intent) |
| Payment notifications | Notification title and text of listed apps | Review queue on the phone | No | Notification access (user grants in system settings) |
| Share to Penny | Shared text/image | Review queue | No | none |
| Sync | Event batches | Relay stores ciphertext only | Yes, encrypted end to end | INTERNET |
| Sign-in | Email (hashed on relay), device model | Relay: email hash, device name | Yes | INTERNET |
| Shared spaces | Space key wrapped per member | Relay: ciphertext + member ids | Yes, encrypted | INTERNET |
| Advisor / group web links | Report summary | Relay: ciphertext; key only in the link | Yes, encrypted | INTERNET |
| Bank connections (Plus) | Bank transactions | Phone; relay keeps only a cursor | Passes through the relay over TLS | INTERNET |
| Email forwarding | Forwarded receipts | Relay encrypts on arrival, deletes after the phone reads it | Yes, encrypted at rest | INTERNET |
| Merchant map (opt-in) | Payee names and category keys, no amounts | Relay, anonymous aggregates | Yes, if turned on | INTERNET |
| Exchange rates | None (public data) | Phone cache | Request to ECB only | INTERNET |
| Webhooks (opt-in) | New transactions | — | Only to the user's own https URL | INTERNET |
| Usage counts (opt-in) | Feature counts only | Phone | No (no upload yet) | none |
| Alerts | Computed from local data | Phone | No | POST_NOTIFICATIONS (asked when turned on) |
| App lock | PIN hash (PBKDF2) | Phone preferences | No | USE_BIOMETRIC |
| Plus / tips | Purchase token | Play; relay gets token for entitlement | Yes | BILLING |
| Folder backup | Full export, encrypted with the recovery phrase key when set | User's chosen folder | Only to the folder the user picks | none (system picker) |

Never requested: READ_SMS, RECEIVE_SMS, contacts, location, microphone (speech uses the system recogniser), ad IDs.
No third-party ad or tracking SDKs.
