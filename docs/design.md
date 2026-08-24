# Skylink Bingwa — UI and UX Design System

**Design direction:** Calm Momentum  
**Platform:** Native Android  
**Product:** Customer-facing bundle purchase app  
**Status:** Canonical version 1 design specification  
**Source of product truth:** `Plan.md`

---

## 1. Purpose

This document defines how Skylink Bingwa must look, feel, communicate and behave.
It is not a collection of visual suggestions. It is the shared design contract
for product design, prototypes, Android implementation, content and quality
assurance.

When this document and a mock-up disagree, this document wins. When a visual
idea conflicts with payment clarity, accessibility, offline reliability or the
product rules in `Plan.md`, those product needs win.

Skylink Bingwa should feel:

- Fast without feeling rushed.
- Sleek without becoming decorative.
- Friendly without becoming childish.
- Commercial without feeling pushy.
- Familiar enough to use immediately.
- Dependable online and offline.

The interface is successful when customers notice the offer, price, recipient,
payment number and next action before they notice the styling.

---

## 2. Experience North Star

> Find a useful offer, understand it and start the correct payment in seconds.

### Core experience promises

1. **Immediate usefulness:** cached offers appear without waiting for a network
   request.
2. **One clear decision:** each step asks one main question and has one dominant
   action.
3. **No payment ambiguity:** the recipient, payer and total are always clearly
   labelled.
4. **Honest status:** version 1 ends at payment confirmation; the app does not
   claim that the bundle was delivered.
5. **Useful offline mode:** losing internet never replaces the app with a dead
   offline page.
6. **Quiet intelligence:** purchase-aware states and notifications help without
   pretending to know the customer's remaining data or device usage.
7. **Easy recovery:** errors explain what happened, preserve input and offer a
   clear next step.

### What “best-ever UX” means here

It does not mean more animation, more colour or novel navigation. It means:

- Fewer moments of uncertainty.
- Fewer taps to a confident purchase.
- Faster perceived and actual response.
- Better defaults for returning customers.
- Clearer offline instructions.
- More useful states and fewer dead ends.
- Consistent behaviour across every offer and payment route.

---

## 3. Non-negotiable Product Truths

Every screen, prototype and message must respect these facts:

- Skylink Bingwa sells only the owner's Bingwa offers.
- Version 1 is Android only.
- Customers do not create an account and do not complete OTP verification.
- The first launch collects a name and primary Safaricom phone number and saves
  them locally.
- A customer may buy for their own number or another number.
- The M-Pesa STK prompt goes to the payer's number.
- Online purchase uses M-Pesa STK Push.
- Offline purchase shows exact Till or Paybill instructions from valid cached
  configuration.
- A successful payment means **Payment received**, not **Bundle delivered**.
- Bundle fulfilment happens outside the customer app.
- Activity, favourites, profile and purchase awareness are local to the current
  installation.
- Daily purchase awareness uses successful Skylink Bingwa purchases and offer policy.
- The app does not inspect remaining data, general device usage, other apps or
  browsing behaviour.
- Referrals, rewards, credits and tokens are not part of version 1.

Never design a state that contradicts these truths.

---

## 4. Design Principles

### 4.1 Clarity before style

The product may look distinctive, but familiar Android patterns must be used for
navigation, forms, search, selection, bottom sheets and payment. A customer
should never need to learn a new interaction in order to buy.

### 4.2 Speed is a visual feature

Local content should render immediately. Pressed states appear instantly.
Background refresh must not block cached offers. Never show a loader for work
that finishes in less than 300ms.

### 4.3 Recognition over recall

Show category names, recent numbers, favourites, purchase states and clear
labels. Do not ask customers to remember whether an offer was bought, which
number will pay or what a vague icon means.

### 4.4 Progressive disclosure

Show the information needed for the current decision. Keep full conditions,
filters and payment instructions available without crowding the first view.

### 4.5 One dominant action

Only one filled primary action may dominate a screen or bottom sheet. Secondary
actions use outline, tonal or text treatment. Orange does not create a second
primary-action system.

### 4.6 Calm commerce

Promotions may be visible, but they must not interrupt payment, auto-rotate,
flash, pulse or create false urgency. A useful offer is more persuasive than a
noisy banner.

### 4.7 Honest system status

Every meaningful state is visible: offline, refreshing, payment request sent,
payment received, payment failed, waiting to verify and bought today. Never use
fake percentages, fake countdowns or premature success.

### 4.8 Preserve customer effort

Selected offers, numbers and form input survive navigation, rotation and a
dropped connection. Failed submission never clears valid input.

---

## 5. Relevant UX Laws and Their Application

Only rules that materially improve Skylink Bingwa are included.

| UX principle | Skylink Bingwa application | Enforcement |
|---|---|---|
| Fitts's Law | Put large primary actions in the lower thumb zone | Minimum 48dp targets; full-width payment CTA |
| Hick's Law | Reduce competing choices | Four primary destinations; progressive filters; one CTA |
| Jakob's Law | Use patterns customers already know | Standard Android navigation, sheets, forms and M-Pesa language |
| Doherty Threshold | Maintain flow through fast feedback | Render local data immediately; feedback within 100ms; skeleton only after 300ms |
| Serial Position Effect | Put the most useful content first and the decisive action last | Search and categories early; sticky confirmation action at the end |
| Law of Proximity | Group related details without excessive boxes | Recipient and payer form a payment-details group; use spacing before dividers |
| Miller's Law | Chunk dense information | Group filters, settings and checkout details instead of long flat lists |
| Von Restorff Effect | Make the single important action distinct | One deep-green primary button; orange reserved for genuine promotions |
| Peak-End Rule | Make the end of payment calm and useful | Clear Payment received state with next steps and support |
| Zeigarnik Effect | Preserve unfinished tasks | Restore pending STK or offline verification state when the app reopens |
| Postel's Law | Accept familiar phone formats, store one canonical format | Accept `07`, `01`, `254` and `+254`; normalize safely |
| Tesler's Law | Let the system absorb avoidable complexity | Prefill local profile values, remember recent numbers and choose the correct payment route |
| Nielsen heuristics | Keep status, control, consistency and recovery visible | Required in the screen QA checklist |

Not every generic UX rule belongs in this app. Desktop command palettes,
right-click menus, data-table pagination, password and OTP patterns are outside
the version 1 customer experience.

---

## 6. Visual Identity

### 6.1 Style name: Calm Momentum

Calm Momentum combines a light, breathable utility interface with controlled
bursts of brand colour. The layout carries most of the sophistication; colour
supports meaning instead of decorating every surface.

### 6.2 Brand colour roles

| Token | Hex | Role |
|---|---:|---|
| Brand green | `#18C964` | Logo accent, selected illustrations, positive accent and brand moments |
| Action green | `#006B27` | Primary buttons, active navigation, selected controls and progress |
| Sky blue | `#3BA9FF` | Data category accent and informational highlights |
| Action blue | `#1565D8` | Accessible links, help actions and information controls |
| Energy orange | `#FF8A00` | Promotions, discounts, limited-offer labels and price highlights |
| Dark navy | `#0A2540` | Primary text, headings and high-contrast content |

### 6.3 Accessibility correction

The bright brand colours do not support white normal text:

| Background | Contrast with white | Required treatment |
|---|---:|---|
| `#18C964` | 2.19:1 | Use dark navy text, or use action green for a white-text button |
| `#3BA9FF` | 2.53:1 | Use dark navy text, or use action blue for a white-text control |
| `#FF8A00` | 2.36:1 | Use dark navy text |

Therefore:

- `#006B27` with white is the normal primary-action pair.
- `#1565D8` with white is the accessible information-action pair.
- Bright green, blue and orange use dark navy text when they carry text.
- No designer or developer may place white body-size text directly on the three
  bright brand colours.

### 6.4 Colour discipline

- Approximately 75% of a normal screen should be neutral surface.
- Green is the only normal primary action colour.
- Blue means information, help or the data category.
- Orange means genuinely promotional or time-sensitive commercial information.
- Semantic colours are not decoration.
- No component may contain multiple competing bright fills.
- Status is always communicated with text and/or an icon, never colour alone.

---

## 7. Colour Tokens

### 7.1 Light theme

| Role | Token | Value |
|---|---|---:|
| App canvas | `background` | `#F6F9FC` |
| Main surface | `surface` | `#FFFFFF` |
| Soft grouped surface | `surface-low` | `#F0F5F2` |
| Raised/selected surface | `surface-high` | `#E8F0EB` |
| Primary text | `on-surface` | `#0A2540` |
| Secondary text | `on-surface-variant` | `#526271` |
| Muted text | `on-surface-muted` | `#687785` |
| Divider | `divider` | `#E5E7EB` |
| Outline | `outline` | `#AEBBC4` |
| Primary action | `primary` | `#006B27` |
| On primary | `on-primary` | `#FFFFFF` |
| Primary pressed | `primary-pressed` | `#00491B` |
| Primary container | `primary-container` | `#DDF7E7` |
| On primary container | `on-primary-container` | `#003D15` |
| Brand accent | `brand-green` | `#18C964` |
| Information action | `info` | `#1565D8` |
| On information | `on-info` | `#FFFFFF` |
| Information container | `info-container` | `#E2F1FF` |
| On information container | `on-info-container` | `#0A2540` |
| Data accent | `sky-blue` | `#3BA9FF` |
| Promotion | `promotion` | `#FF8A00` |
| On promotion | `on-promotion` | `#0A2540` |
| Promotion container | `promotion-container` | `#FFF0DC` |
| On promotion container | `on-promotion-container` | `#653600` |
| Success | `success` | `#157A3B` |
| Success container | `success-container` | `#DDF7E7` |
| Warning | `warning` | `#9A5A00` |
| Warning container | `warning-container` | `#FFF2D5` |
| Error | `error` | `#BA1A1A` |
| Error container | `error-container` | `#FFDAD6` |
| Disabled surface | `disabled-container` | `#E7ECE9` |
| Disabled content | `on-disabled` | `#66726B` |
| Scrim | `scrim` | `rgba(10, 37, 64, 0.48)` |

### 7.2 Dark theme

Dark mode is designed, not inverted.

| Role | Token | Value |
|---|---|---:|
| App canvas | `background` | `#0E1512` |
| Main surface | `surface` | `#15201B` |
| Soft grouped surface | `surface-low` | `#19251F` |
| Raised/selected surface | `surface-high` | `#233129` |
| Primary text | `on-surface` | `#EEF7F1` |
| Secondary text | `on-surface-variant` | `#BAC9BF` |
| Muted text | `on-surface-muted` | `#94A39A` |
| Divider | `divider` | `#303D35` |
| Outline | `outline` | `#697970` |
| Primary action | `primary` | `#70DC8A` |
| On primary | `on-primary` | `#003D15` |
| Primary container | `primary-container` | `#00531E` |
| On primary container | `on-primary-container` | `#C2F4CC` |
| Information | `info` | `#9DC1FF` |
| On information | `on-info` | `#002E66` |
| Information container | `info-container` | `#0C438F` |
| Promotion | `promotion` | `#FFB45C` |
| On promotion | `on-promotion` | `#4C2800` |
| Promotion container | `promotion-container` | `#683800` |
| Success | `success` | `#72D687` |
| Warning | `warning` | `#FBC65A` |
| Error | `error` | `#FFB4AB` |
| Error container | `error-container` | `#93000A` |
| Disabled surface | `disabled-container` | `#263029` |
| Disabled content | `on-disabled` | `#98A49C` |
| Scrim | `scrim` | `rgba(0, 0, 0, 0.72)` |

### 7.3 Category accents

Category colours help scanning but never replace category labels.

| Category | Accent | Container | Text/icon |
|---|---:|---:|---:|
| Data | `#3BA9FF` | `#E2F1FF` | `#0A2540` |
| Minutes | `#18C964` | `#DDF7E7` | `#0A2540` |
| SMS | `#7C6CF2` | `#EEEAFE` | `#302269` |
| Special offers | `#FF8A00` | `#FFF0DC` | `#653600` |

Use these accents in a small icon container, indicator or chip—not as full
screen backgrounds.

---

## 8. Typography

### 8.1 Typeface decision

- **Outfit:** display text, major page headings and large prices.
- **Poppins:** body copy, labels, controls, forms, navigation and metadata.
- **Fallback:** Android system sans-serif.

The two-family system is an intentional exception to the single-family default:
Outfit gives the brand a recognisable voice, while Poppins remains the only
functional UI typeface. Do not introduce a third family.

Bundle only required font files or validated variable fonts. Typography must
remain readable if Android falls back to the system font.

### 8.2 Type scale

All Android sizes are `sp`.

| Style | Typeface | Size / line | Weight | Use |
|---|---|---:|---:|---|
| Display | Outfit | 32 / 40 | 700 | Rare hero value or onboarding heading |
| Headline large | Outfit | 28 / 36 | 700 | Main page heading |
| Headline medium | Outfit | 24 / 32 | 600 | Payment and section headline |
| Headline small | Outfit | 20 / 28 | 600 | Sheet title and prominent card |
| Title large | Poppins | 18 / 26 | 600 | Section and offer title |
| Title medium | Poppins | 16 / 24 | 600 | Row and card title |
| Body large | Poppins | 16 / 24 | 400 | Default body and input value |
| Body medium | Poppins | 14 / 21 | 400 | Supporting explanation |
| Label large | Poppins | 16 / 22 | 600 | Primary button |
| Label medium | Poppins | 14 / 20 | 600 | Control and chip |
| Label small | Poppins | 12 / 16 | 500 | Short metadata only |
| Price large | Outfit | 30 / 36 | 700 | Review total |
| Price medium | Outfit | 22 / 28 | 700 | Offer card price |

### 8.3 Typography rules

- Body text never drops below 14sp; 12sp is reserved for short metadata.
- Use no more than three weights on one screen.
- Use sentence case everywhere.
- Left-align body copy and instructions.
- Keep paragraphs short; instructional lines should scan independently.
- Use tabular numerals for prices and transaction values where supported.
- Allow Android font scaling up to at least 200% without clipping or hiding
  actions.
- Do not bake text into images.
- Never use all caps for headings or buttons.

---

## 9. Layout, Spacing and Shape

### 9.1 Measurement

- Use `dp` for layout and icon measurements.
- Use `sp` for text.
- Foundation spacing unit: 8dp.
- A 4dp half-step is allowed for optical alignment and compact internal gaps.

| Token | Value |
|---|---:|
| `space-0` | 0dp |
| `space-0.5` | 4dp |
| `space-1` | 8dp |
| `space-1.5` | 12dp |
| `space-2` | 16dp |
| `space-3` | 24dp |
| `space-4` | 32dp |
| `space-5` | 40dp |
| `space-6` | 48dp |

### 9.2 Mobile layout

- Design reference viewport: 360–412dp wide.
- Horizontal screen gutter: 16dp.
- Major section gap: 24dp.
- Related control gap: 12dp.
- Compact metadata gap: 4–8dp.
- Minimum touch target: 48×48dp.
- Focused form and checkout content remains single-column.
- Use edge-to-edge layout while respecting status, navigation and keyboard
  insets.
- Sticky actions sit above the system navigation inset and never cover content.

On tablets, keep purchase and form content between 480–640dp rather than
stretching it across the screen.

### 9.3 Radius

| Token | Value | Use |
|---|---:|---|
| `radius-small` | 8dp | Compact tags and small icon containers |
| `radius-control` | 12dp | Buttons, fields and segmented controls |
| `radius-card` | 16dp | Offer and content cards |
| `radius-prominent` | 20dp | Large promotion or status surface |
| `radius-sheet` | 24dp | Top corners of bottom sheets |
| `radius-full` | 999dp | Avatars, toggles and compact status chips only |

Do not make every button pill-shaped. Do not use sharp rectangular cards.

### 9.4 Elevation

Depth comes from tonal surfaces first.

| Level | Treatment | Use |
|---|---|---|
| 0 | No shadow | Screen canvas and grouped regions |
| 1 | `0 2dp 10dp rgba(10,37,64,.05)` | Cards only when tonal separation is insufficient |
| 2 | `0 8dp 24dp rgba(10,37,64,.10)` | Floating bars and menus |
| 3 | `0 16dp 36dp rgba(10,37,64,.14)` | Modal sheets and blocking surfaces |

- Buttons do not need shadows.
- Avoid using both a visible border and shadow on the same normal card.
- Dark theme uses surface contrast and subtle outlines instead of large shadows.

---

## 10. Iconography and Imagery

### 10.1 Icons

- Use **Material Symbols Rounded or Outlined** as the canonical Android icon
  family.
- Use one family consistently; do not mix it with Lucide in production.
- The “Lucide React” instruction from the web-oriented proposal does not apply
  to the native Android build.
- Standard sizes: 16dp metadata, 20dp controls, 24dp navigation, 28–32dp status.
- Use outlined icons for inactive states and a consistent selected treatment.
- Pair unfamiliar icons with a text label.
- Icon-only controls require an accessibility label and a 48dp touch target.
- Icons must communicate a real action, category or state.
- **Zero emoji:** no emoji in buttons, badges, headings, alerts or empty states.

### 10.2 Illustration

Use illustration sparingly:

- One simple brand illustration may appear in onboarding.
- Empty states use a small outlined or low-detail illustration.
- Payment states use clear symbolic illustrations, not celebratory scenes.
- Keep vector assets lightweight and effective in light and dark themes.
- Do not use generic stock photography, 3D telecom mascots or decorative
  lifestyle imagery inside purchase flows.

### 10.3 Logo

Required logo family:

- Full horizontal lock-up.
- Compact mark.
- Monochrome light and dark variants.
- Android adaptive icon foreground and background.
- Notification icon as a true monochrome silhouette.

Never stretch, add glow, place on a low-contrast colour or recolour individual
parts outside the approved variants.

---

## 11. Motion and Haptics

Motion confirms cause and effect. It does not entertain.

| Interaction | Duration | Behaviour |
|---|---:|---|
| Press/selection feedback | 80–120ms | Scale or tonal response, no bounce |
| Chip/card selection | 160–180ms | Border, colour and check transition |
| Page transition | 220–260ms | Standard Android directional transition |
| Bottom-sheet entrance | 240–280ms | Translate and fade |
| Bottom-sheet exit | 180–220ms | Slightly faster than entrance |

Use a standard non-overshooting easing such as
`cubic-bezier(0.2, 0, 0, 1)`.

Rules:

- Respect Android reduced-motion settings.
- Use subtle haptic feedback for a confirmed selection and important success,
  where device settings allow it.
- Do not use haptics for every tap.
- No bounce, confetti, pulsing CTA, parallax, looping shimmer or auto-moving
  promotion.
- Payment processing may use an indeterminate progress indicator, but never a
  fake percentage.

---

## 12. Navigation and Information Architecture

### 12.1 Primary navigation

Use a persistent four-item bottom navigation:

1. **Home**
2. **Offers**
3. **Activity**
4. **Help**

Every item has a 24dp icon and text label. The selected destination uses action
green and a soft green indicator/container. Orange is never used for
navigation.

Settings opens from the profile/avatar control in the Home header or a clearly
labelled entry in Help. It is not a fifth bottom-navigation item.

### 12.2 Navigation behaviour

- Android back returns to the previous state or destination predictably.
- Returning from offer details restores the previous list position and filters.
- Reselecting the current bottom destination returns to its root and may scroll
  to top.
- Purchase flow may temporarily hide bottom navigation to protect focus.
- Exiting an unsubmitted purchase requires no warning if nothing would be lost.
- If meaningful input would be discarded, preserve it as a draft or explain the
  consequence.

### 12.3 Screen header

Use a compact top app bar:

- Page title or Skylink Bingwa mark.
- Optional notification control.
- Optional profile/settings control.
- No duplicate title when the first content heading already names the page.

---

## 13. Component System

### 13.1 Action hierarchy

| Level | Style | Use |
|---|---|---|
| Primary | Action green fill, white text | One main outcome: Buy bundle, Pay KSh 50, Save changes |
| Secondary | White/tonal fill, navy text, outline where needed | Change details, View activity |
| Informational | Blue text or blue tonal control | Help, Learn more, View terms |
| Tertiary | Text-only, navy or blue | Low-emphasis reversible action |
| Destructive | Error text/outline; filled only for final destructive commit | Clear local data |

### 13.2 Buttons

**Primary button**

- Height: 52dp.
- Minimum width: 96dp; full width in checkout.
- Horizontal padding: 20dp.
- Radius: 12dp.
- Fill: `primary`.
- Label: `on-primary`, Poppins 16sp/600.
- Optional leading icon: 20dp.
- Pressed fill: `primary-pressed`.
- Loading: retain width, replace leading icon with progress, disable repeat tap.

**Secondary button**

- Height: 48dp.
- Surface or transparent background.
- Navy label.
- 1dp outline where the boundary is otherwise unclear.

**Promotion action**

There is no separate orange purchase button. A promotional offer still uses the
green primary action. Orange belongs to its promotion chip, changed price or
small emphasis.

Button copy names the outcome:

- Buy bundle
- Pay KSh 50
- View offline steps
- Resend prompt
- Save changes

Never use **Submit**, **Proceed**, **Go**, **OK** or **Click here**.

### 13.3 Text fields

- Height: minimum 56dp.
- Single-column layout.
- External label remains visible.
- Fill: surface.
- Border: 1dp outline; 2dp primary on focus.
- Radius: 12dp.
- Horizontal padding: 16dp.
- Input text: Poppins 16sp.
- Supporting/error text: 14sp.
- Numeric keypad for phone fields.
- Do not split a phone number into multiple boxes.

Required states:

- Default.
- Focused.
- Valid.
- Error.
- Disabled.
- Loading/read-only.

Validate phone fields on blur. After an error appears, update that field live and
clear the error as soon as it becomes valid. Preserve the typed value after any
failure.

Phone field labels are explicit:

- **Bundle recipient**
- **M-Pesa payment number**

Example text may show `0712 345 678`, but the placeholder never replaces the
label.

### 13.4 Search

- Full-width, 52dp-high search field.
- Leading search icon, persistent label or hint:
  **Search data, SMS or minutes**.
- Clear action appears when text exists.
- Local results update immediately.
- Network-backed suggestions, if added, debounce by 250–350ms.
- Empty search shows recent searches or popular categories.
- No-results and filtered-out states are distinct.

### 13.5 Filter chips

- Minimum 48dp touch target even if the visible chip is smaller.
- States: idle, active and unavailable.
- Active chip uses a primary container, primary text and check icon.
- Show **Clear filters (n)** when any filters are active.
- Update the visible result count immediately.
- Use a bottom sheet for price and validity options when chips would overflow.

### 13.6 Category shortcuts

Use four compact category tiles:

- Data.
- Minutes.
- SMS.
- Special offers.

Each uses a small category-colour icon container, text label and 48dp target.
They may scroll horizontally only if the viewport cannot fit them without
shrinking targets. Do not show icons without labels.

### 13.7 Offer card

The card is a selection surface, not a miniature checkout screen.

Required content:

1. Allowance or offer name.
2. Price.
3. Validity.
4. At most one commercial label.
5. Favourite control.
6. Purchase/daily state where relevant.

Specification:

- Surface background.
- 16dp radius.
- 16dp padding.
- Minimum height: 112dp.
- 1dp divider/outline or level-1 elevation, not both by default.
- Whole card is tappable.
- Favourite control has its own 48dp target.
- Compact cards do not contain a full-size Buy button.

The offer and price have strongest hierarchy. Validity and eligibility are
supporting text. A promotional chip may say **Limited offer** or **Best value**.
Never stack multiple badges.

### 13.8 Offer purchase state

| State | Presentation | Interaction |
|---|---|---|
| Available today | Neutral or soft-green label | Card remains selectable |
| Bought today | Check icon + green-tonal label | Hard-limited offer disabled for that recipient |
| Available tomorrow | Clock icon + next eligible time | Explain why it cannot be bought now |
| `n` purchases left today | Count label | Card remains selectable |
| Waiting to verify | Clock icon + warning-tonal label | Prevent accidental duplicate on hard-limited offer |
| Offline unavailable | Offline icon + explanation | Keep visible; disable purchase route |

Do not use strikethrough or error red for a legitimate daily limit.

### 13.9 Cards and grouped surfaces

- Use cards for selectable offers, promotions and distinct status summaries.
- Use plain grouped sections for ordinary page content.
- Avoid cards inside cards.
- Avoid wrapping every settings row or transaction in an independent card.
- Use whitespace and tonal background before adding borders.

### 13.10 Status strip

Use a compact, non-blocking strip beneath the app bar.

**Offline example**

- Offline icon.
- **You're offline**
- **Saved offers are available. Pay using M-Pesa instructions.**

The strip must not appear as an error if offline purchase remains possible.
It may collapse after acknowledgement but connection state stays discoverable.
Do not notify the customer every time connectivity changes.

### 13.11 Bottom sheets

Use for:

- Short offer details.
- Recipient choice.
- Compact filter choices.
- Purchase action confirmation.

Specification:

- Surface background.
- 24dp top corners.
- 24dp padding.
- Drag handle only when the sheet is dismissible.
- Clear title and close/back behaviour.
- Sticky action above system/keyboard insets.

Use a full page when the customer must type multiple values, follow long offline
instructions or handle an important payment state.

### 13.12 Dialogs

Use dialogs only for blocking or irreversible decisions:

- Clearing all local data.
- Abandoning a payment state when the consequence is material.
- A critical payment interruption.

Buttons must name the action. **Clear local data** and **Keep my data** are
acceptable; **Yes** and **No** are not.

### 13.13 Toasts and snackbars

- Use an Android snackbar near the bottom, above navigation.
- Information: about 4 seconds.
- Warning: about 7 seconds.
- Errors that require action remain until dismissed or resolved.
- Maximum one customer-action snackbar at a time.
- Include icon/text, not colour alone.
- Reversible changes such as removing a favourite provide **Undo**.
- Payment confirmation is never reduced to a transient toast.

### 13.14 Lists

Activity and settings use rows rather than oversized cards.

- Minimum row height: 72dp for Activity; 56dp for simple Settings.
- 12–16dp vertical padding.
- Use dividers only between items in the same group.
- Preserve readable date, amount, recipient and status hierarchy.
- Status colour stays in the icon/label, not the whole row.

### 13.15 Empty and error states

Never show a blank screen. Distinguish:

- First use.
- No purchases yet.
- No search results.
- Filters removed all results.
- Cached data unavailable.
- Temporary refresh error.

Each state has:

- One meaningful icon or small illustration.
- A direct heading.
- One sentence.
- A relevant next action.

Example:

**No purchases yet**  
Bundles you pay for in Skylink Bingwa will appear here.  
**Browse offers**

---

## 14. Screen Blueprints

These define hierarchy, not fixed wireframes. Designers may improve composition
without changing meaning or action order.

### 14.1 Android launch

- Use the Android system splash only.
- Centre the adaptive logo mark.
- No extra loading slogan, carousel or second splash page.
- Move directly to onboarding or cached Home.

### 14.2 Welcome and onboarding

Use a short functional flow:

1. **Welcome**
   - Brand mark.
   - Headline: **Bundles without the hassle.**
   - One sentence covering data, SMS, minutes and special offers.
   - Primary action: **Get started**.
2. **Your details**
   - Name.
   - Primary Safaricom phone number.
   - Reassurance: **Saved only on this phone. No account is created.**
   - Primary action: **Continue**.
3. **Useful notifications**
   - Explain transaction updates and restrained helpful reminders.
   - Primary action: **Turn on notifications**.
   - Secondary action: **Not now**.
4. **Home**

Use a compact progress indicator. Do not show promotional feature-tour slides.
Only trigger Android's notification permission after the customer accepts the
soft explanation.

### 14.3 Home

Content order:

1. Compact header with first-name greeting, notification and profile controls.
2. Search.
3. Four category shortcuts.
4. Compact connectivity strip when needed.
5. One restrained promotion.
6. Popular offers.
7. Bought today, when relevant.
8. More offers you can buy.
9. Buy again for repeatable offers.
10. Favourites, when present.

Rules:

- Useful offers should appear above the fold on a common Android phone.
- Never use an auto-rotating carousel.
- Show at most one large promotion above the fold.
- Use horizontal offer rows only when each card remains readable and the next
  item is visibly discoverable.
- Personalise the greeting once; do not repeat the customer's name throughout.
- Do not say **Recommended for your data usage**.

### 14.4 Offers

- Search remains available.
- Category controls appear before secondary filters.
- Show result count.
- Offer list uses compact, scan-friendly cards.
- Preserve query, filters, sort and scroll position.
- Filters: category, price and validity.
- Sort: Popular, Lowest price, Highest value, Shortest validity, Longest
  validity.
- Offline mode shows valid cached offers and identifies unavailable purchases
  inline.

### 14.5 Offer details

Use a bottom sheet for the normal case:

- Allowance/name.
- Price.
- Validity.
- Eligibility or time restrictions.
- Daily state.
- Favourite control.
- Primary action: **Buy bundle**.

Long conditions may open a full page. Do not bury a critical restriction behind
an info icon.

### 14.6 Recipient and payer

First ask:

- **For my number**
- **For another number**

Then show:

- Bundle recipient.
- M-Pesa payment number.
- Recent numbers, when available.
- Optional contact picker after an explicit tap.

For **For my number**, prefill both values from the local profile. For another
number, prefill the payer with the profile number and focus the recipient field.

Do not request broad contacts access during onboarding.

### 14.7 Review purchase

The final review contains:

| Information | Emphasis |
|---|---|
| Offer | High |
| Bundle recipient | Clearly labelled |
| M-Pesa payment number | Clearly labelled |
| Total | Highest |

Use a sticky primary action:

- Online: **Pay KSh {amount}**
- Offline: **View offline payment steps**

Secondary action: **Change details**.

No promotional banner, unrelated offer or notification prompt appears here.

### 14.8 Online M-Pesa flow

After starting STK:

- Headline: **Check your phone**
- State the exact masked/full payment number as appropriate on the in-app screen.
- Explain that the customer should enter their M-Pesa PIN on that phone.
- Show a restrained indeterminate progress indicator.
- Allow **Change payment number** before payment succeeds.
- Reveal **Resend prompt** only after the controlled delay.
- Restore this state if the app is reopened.

Outcomes:

- **Payment received** — success icon, offer, recipient, amount, local reference
  and **Done**.
- **Payment cancelled** — neutral explanation and **Try again**.
- **Payment failed** — human explanation, preserved details and **Try again**.
- **Still checking payment** — explain the delay and offer Activity or support.

Never show **Bundle delivered** in version 1.

### 14.9 Offline payment flow

Start with a calm context banner:

**No internet needed for these steps**  
Use the exact business number and amount shown below.

For the customer's own number, show numbered Till instructions. For another
recipient, show numbered Paybill instructions with the recipient number as the
account reference.

Design rules:

- One numbered step per row.
- Business number, account and exact amount use high-contrast copyable value
  blocks.
- Copy actions have text labels and confirmation feedback.
- The exact amount is repeated in the final review.
- Warn the customer not to change the amount or account.
- Display the saved-configuration timestamp/validity only when it is useful for
  trust or troubleshooting.
- If cached instructions have expired, disable payment and explain that internet
  is needed to refresh them.

After the customer indicates that they paid:

- Use **Waiting to verify payment**, not success.
- Keep the attempt in Activity.
- Do not claim payment or bundle delivery while offline.

### 14.10 Activity

Show locally stored purchase records in reverse chronological order.

Each row contains:

- Offer.
- Amount.
- Recipient.
- Date and time.
- Honest payment state.

Use **Activity**, not **Orders**, because records may include pending or failed
attempts. Group by Today, Yesterday and earlier dates where this improves
scanning.

### 14.11 Help

- Searchable FAQ.
- Report a purchase problem.
- WhatsApp support.
- Call support when configured.
- Privacy policy.
- Terms.
- App version.

When opened from an Activity item, prefill the order reference and status, never
an M-Pesa PIN or other secret.

### 14.12 Settings

Group rows into:

**Profile**

- Name.
- Primary Safaricom phone number.

**Notifications**

- Order updates.
- Offers and promotions.
- Helpful reminders.
- Personalised purchase messages.
- App updates.
- Android system notification settings.

**Appearance**

- System default.
- Light.
- Dark.

**About**

- About Skylink Bingwa.
- Version name.
- Version code where helpful.
- Privacy policy.
- Terms.
- Contact support.

**Local data**

- Explain that profile, favourites and Activity belong to this installation.
- Destructive action: **Clear local data** with explicit confirmation.

---

## 15. Offline-First Experience Rules

Offline is a product mode, not an error page.

- Load the last valid catalogue from local storage immediately.
- Show connection state without blocking browsing.
- Keep search, filters, favourites, Activity and profile functional.
- Preserve in-progress input and selected offer.
- Mark any data whose offline-validity window has ended.
- Disable only the action that cannot be completed safely; keep the explanation
  and alternative visible.
- Text and essential controls load before imagery.
- Compress images and avoid unnecessary remote assets.
- A failed refresh leaves cached content in place and offers **Try again**.
- When connectivity returns, refresh quietly and announce meaningful changes
  without resetting scroll position.

Do not:

- Replace the app with a full-screen **You're offline** page.
- Show stale prices as current.
- silently switch an STK purchase to offline instructions.
- Claim that a remote notification can arrive while the phone has no internet.
- fire a local notification every time internet disconnects.

---

## 16. Daily Purchase Awareness

This is purchase-policy awareness, not data recommendation.

The UI may use:

- Successful purchases recorded by Skylink Bingwa.
- Recipient number.
- Offer purchase limits.
- The Nairobi calendar day.

The UI may say:

- **Bought today**
- **Available again tomorrow**
- **2 purchases left today**
- **You bought this for 0712 ••• 678 today**
- **More offers you can buy**

The UI must not say:

- **You are running out of data**
- **You need more data**
- **Based on your browsing**
- **Recommended for your usage**

For once-per-day offers, the disabled state must explain the limit and next
eligible time. Other eligible offers may be shown as alternatives without
claiming they are personalised data recommendations.

---

## 17. Notification UX

### 17.1 Permission experience

Do not request notification permission on the first frame. After profile setup,
show a soft explanation:

**Stay updated without the noise**  
Get payment updates and a small number of useful offer reminders. You can change
each type in Settings.

Actions:

- **Turn on notifications**
- **Not now**

Only the first action opens the Android permission request. Denial never blocks
the app.

### 17.2 Notification hierarchy

| Channel | Visual priority | Customer control |
|---|---|---|
| Order updates | Highest, factual | Separate channel |
| Helpful reminders | Medium, personalised carefully | Toggle |
| Offers and promotions | Medium/low | Toggle |
| App updates | Low unless critical | Toggle |

### 17.3 Message design

- Title: ideally 35 characters or fewer.
- Body: ideally two short lines or fewer.
- Use first name sparingly.
- Never expose a full phone number, M-Pesa receipt or sensitive payment data on
  the lock screen.
- One clear destination per notification.
- Deep-link to the relevant offer, Activity item or update explanation.
- Expired offers must not open to a dead purchase screen.

Examples:

- **Payment received**  
  Please wait for the bundle on 0712 ••• 678.
- **Available again today**  
  Your saved 1 GB offer can be bought again.
- **Still useful offline**  
  Open Skylink Bingwa to buy from your saved offers using M-Pesa.

### 17.4 Restraint

- Marketing and local engagement reminders share a cap of two per seven days.
- Keep at least 48 hours between them.
- Quiet hours: 8:00 PM–8:00 AM, Africa/Nairobi.
- Suppress reminders for 24 hours after a completed purchase.
- Pause after three ignored reminders.
- Deduplicate local and remote messages.
- Prefer silence when the message is not timely, specific and useful.

---

## 18. Feedback, Loading and Error Recovery

### 18.1 Loading

- Under 300ms: no loading UI.
- 300ms–3s with known layout: matched skeleton.
- Unknown short wait: small inline progress indicator.
- Long wait: state what is happening and provide an escape or support path.
- Never block cached content while refreshing.
- Never use optimistic success for payment.

### 18.2 Errors

Match the surface to severity:

- Field problem: inline error beside the field.
- Temporary background problem: snackbar.
- Section refresh problem: inline section message with retry.
- Blocking payment problem: full state or dialog only when required.

Every error:

- Uses human language.
- Explains whether money may have been deducted.
- Preserves input.
- Offers a next step.
- Includes support when retry cannot safely resolve it.

Good:

**We couldn't send the M-Pesa prompt**  
Your details are still here. Check your connection and try again.

Bad:

**Error 500**

### 18.3 Destructive actions

- Removing a favourite is immediate and reversible with **Undo**.
- Clearing all local data requires a named confirmation and explains exactly
  what will be removed.
- Never use a confirmation dialog for harmless navigation.

---

## 19. Content and Voice

### 19.1 Voice

Skylink Bingwa speaks like a calm, capable Kenyan service:

- Direct.
- Brief.
- Familiar.
- Respectful.
- Specific.
- Never robotic or overly excited.

Use words customers already know: bundle, offer, M-Pesa, Till, Paybill, recipient
and payment number. Avoid internal automation or API terminology.

### 19.2 Copy rules

- Use sentence case.
- Address the customer as **you**.
- Button labels begin with a verb and name the result.
- Use **KSh 50**, not mixed currency formats.
- Keep payer and recipient labels visible.
- Use familiar Kenyan phone formatting on screen; store canonical E.164.
- Use Nairobi time for daily labels.
- Avoid excessive exclamation marks.
- Do not use **kindly**, **beneficiary**, **initiate transaction**,
  **successful operation** or **telecommunications package**.

### 19.3 Truthful payment language

Approved:

- Check your phone
- Payment request sent
- Payment received
- Payment cancelled
- Waiting to verify payment
- Please wait for the bundle
- Report a purchase problem

Not approved in version 1:

- Bundle delivered
- Data activated
- Bundle confirmed
- Delivery successful

---

## 20. Accessibility

Accessibility is a release requirement.

- Body-text contrast: minimum 4.5:1.
- Large text and meaningful UI boundaries: minimum 3:1.
- Touch targets: minimum 48×48dp.
- Text scaling: test at 100%, 130%, 150% and 200%.
- Status never relies on colour alone.
- All icon-only controls have accessible names.
- TalkBack order follows visual reading order.
- Headings expose semantic hierarchy.
- Buttons expose role, label and disabled/loading state.
- Form errors are announced and associated with their field.
- Bottom sheets and dialogs move accessibility focus inside and return it on
  close.
- Keyboard focus is visible where hardware keyboard or switch access is used.
- Reduced motion is respected.
- Do not use time-limited reading interactions.
- Copy buttons announce what was copied.
- Sensitive values use suitable lock-screen privacy.

Accessibility QA must include a low-cost Android device, bright-light viewing
and dark-mode testing—not only emulator checks.

---

## 21. Prohibited Patterns

Do not ship:

- Gradients.
- Emojis.
- Glassmorphism.
- Neon glows.
- Oversized shadows.
- Auto-rotating carousels.
- Pulsing or bouncing purchase buttons.
- Confetti after payment.
- More than one dominant CTA in the same section.
- White text on bright green, sky blue or orange.
- Orange for ordinary purchase buttons or navigation.
- Large bright surfaces behind ordinary content.
- Tiny text.
- Icon-only primary navigation.
- Labels hidden inside placeholders.
- Vague payment buttons.
- Nested cards without a separate interaction.
- Full-screen offline dead ends.
- Fake delivery confirmation.
- Fake urgency, scarcity or social proof.
- Hidden fees or changing totals.
- Preselected upsells or marketing consent.
- Forced account creation.
- “Recommended” claims based on data usage the app cannot observe.
- Marketing inside checkout, payment or error recovery.

---

## 22. Design Assets

The version 1 asset pack must include:

### Brand

- Master vector logo.
- Horizontal and stacked lock-ups.
- Compact mark.
- Light, dark and monochrome variants.
- Clear-space and minimum-size guidance.

### Android

- Adaptive launcher icon.
- Monochrome themed icon.
- Notification icon.
- Splash-screen mark.
- Play Store 512×512 icon.
- Direct-APK icon consistency.

### Product UI

- One lightweight onboarding illustration.
- Empty Activity illustration/icon.
- No-results illustration/icon.
- Offline-mode illustration/icon.
- Payment received icon/illustration.
- Payment failed icon/illustration.
- Waiting-to-verify icon/illustration.

Every asset must have a defined dark-theme treatment and export scale. Avoid
large raster assets that weaken offline performance.

---

## 23. Screen Review Checklist

Before approving any screen, answer:

### Task and hierarchy

- Is the customer's main goal obvious within two seconds?
- Is there only one dominant action?
- Are offer, recipient, payer and total clear where relevant?
- Is important information visible rather than hidden behind memory or icons?

### Product truth

- Does the screen avoid claiming bundle delivery?
- Does it distinguish payer from recipient?
- Does offline behaviour match the valid cached route?
- Does purchase awareness avoid data-usage claims?

### States and recovery

- Are loading, empty, offline, disabled, error and success states designed?
- Is entered data preserved?
- Is every failure paired with a useful next action?
- Is payment status honest and restorable?

### Visual quality

- Are tokens used instead of one-off colours and sizes?
- Is spacing consistent on the 8dp system?
- Is orange restricted to promotion?
- Is the screen mostly neutral and visually calm?
- Are icons consistent and free of emoji?

### Accessibility

- Does text meet contrast requirements?
- Are targets at least 48dp?
- Does the screen survive 200% text scaling?
- Is status conveyed without colour alone?
- Is TalkBack reading order correct?

### Ethics and restraint

- Are all prices and consequences clear before payment?
- Is urgency genuine?
- Are permissions requested in context?
- Is any promotional element competing with checkout or recovery?

A screen is not complete until its important empty, offline, loading, error and
large-text states have also been reviewed.

---

## 24. Implementation Contract

Build design primitives in this order:

1. Colour, typography, spacing, shape, motion and elevation tokens.
2. Buttons, fields, icons, chips, cards, rows, status strips and navigation.
3. Offer card, purchase summary, payment state and offline instruction
   composites.
4. Screens.
5. Light, dark, offline, loading, error and accessibility variants.

For Android:

- Implement with Material 3 foundations and custom Skylink Bingwa tokens.
- Do not rely on default Material colours without mapping them to this system.
- Use Compose previews for light/dark, font scale and common device widths.
- Keep semantic token names in code; never scatter raw hex values through
  components.
- Reuse one component for the same meaning across Home, Offers and Activity.
- Treat the copy in payment states as product logic, not decorative placeholder
  text.

---

## 25. Final Direction

Skylink Bingwa should look unmistakably modern, but its strongest impression should
be confidence:

- The catalogue is already there.
- The price is obvious.
- The right phone numbers are clear.
- Online and offline routes make sense.
- The app says exactly what it knows.
- The next useful action is always visible.

That combination—not visual excess—is the “crazy good” experience Skylink Bingwa
should own.
