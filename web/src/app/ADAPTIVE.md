# Adaptive shell: guide for the screen packages (v4)

V4-WSHELL built the shell; the screen packages build their screens on it. Spec references (§) are to
`v4-spec.md`. Rule of thumb: **layout inside a screen is CSS** (container queries on the `pane`
container); only the shell, the router glue and the brew screen read the window class in JS.

## 1. Window class: `app/windowClass.ts`

| Class | Width | Nav | `data-wc` |
|---|---|---|---|
| compact | < 600 | bottom bar | `compact` |
| medium | 600-839 | rail 80 | `medium` |
| expanded | 840-1199 | rail 80 | `expanded` |
| large | 1200-1599 | sidebar 248 (rail when collapsed) | `large` |
| xl | >= 1600 | sidebar 248 (rail when collapsed) | `xl` |

`short` = height < 480 (`<html data-short>`); `landscape` = width > height.

```ts
import { useWindowClass, atLeast, isWideLandscape } from "@/app/windowClass";
const wc = useWindowClass();                // { width: "large", short: false, landscape: true }
const layout = wc.short ? "short" : isWideLandscape(wc) ? "stage" : "portrait"; // brew §3.5
if (atLeast(wc.width, "expanded")) { /* … */ }
```
Also: `widthClassFor(px)`, `windowClassFor(w, h)` (pure), `QUERIES` (the literal media queries).

**CSS media queries** (shell only, written literally): `(max-width: 599.98px)`, `(min-width: 600px)`,
`(min-width: 840px)`, `(min-width: 1200px)`, `(min-width: 1600px)`, `(max-height: 479.98px)`.

**Container queries** (screens): every tab layer, detail layer and pane is
`container: pane / inline-size`; sheet bodies are `container: sheet / inline-size`.
```css
@container pane (min-width: 880px) { .detail { grid-template-columns: 340px 1fr; } }
```

### Tokens (`design/tokens.css`, §1.2)
`--nav-w` (0 / 80 / 248), `--gutter` (16 / 24 / 32), `--list-bottom-pad` (112+safe on compact,
32 after), `--list-pane-w` (360 / 400 / 420), `--primary-max` (100% / 420), `--measure` (none /
72ch, also the `.measure` class), `--pane-gap`, `--detail-max` (960, 1080 at XL), `--column-max`
(640 compact, 720 from medium), `--surface0` (nav and list panes: one shade above `--bg`).
Primary buttons at medium+: `max-width: var(--primary-max)`.

## 2. Navigation (`app/Nav.tsx`, `app/BottomBar.tsx`)
Nothing to do in screens. Bar on compact, rail on medium/expanded (and large when collapsed),
sidebar on large/XL. `[` or Ctrl/Cmd+\ collapses (localStorage `coffee.sidebar`). Start brew runs
the command `brew.next` (register it, §5), else opens Recipes. The live-brew pill/row shows the
running brew whenever the brew screen is not on the stack. Focus modes (`brew`, `review`,
`onboarding`, `recipeEdit`) cover the nav and make it `inert`; on compact every layer does.
Other details sit beside the rail/sidebar (`inset-inline-start: var(--nav-w)`).

## 3. Panes: `app/panes.tsx` (§2.3)

```tsx
import { ListDetail, usePane, useSelectInPane, useSelectedPath, usePaneMode, openInTab } from "@/app/panes";

export function RecipesScreen() {
  return (
    <ListDetail
      tab="recipes"
      listLabel="Recipes"
      list={<RecipeList />}                          // today's whole screen on compact/medium
      defaultDetail={<RecipeDetailScreen id={nextId} from="recipes" />}  // never empty
    />
  );
}
```
- Pane-able routes: `recipe`, `brewDetail`, `compare` (`PANEABLE_KINDS` in routes.ts). At
  expanded+, a pane-able route at stack index 1 above **its own tab** renders in that tab's detail
  pane, but only while that tab has a mounted `ListDetail`. Without one (or below 840) it is
  today's full layer. Anything above it (brew, review, editor) is still a focus layer.
- In-tab selection without a route (Gear beans/grinders/settings): pass `detail={<BeanEditor …/>}`
  and `detailKey={bean.uid}`; the route is then ignored and a key change animates like a selection.
- `useSelectInPane(tab)` → `select(path, opts?)`: **push** from the default, **replace** over a
  selection (one Back returns to the default); a no-op if already selected. Use it for every
  selection in a list; at compact it is a plain push.
- `useSelectedPath(tab)` → the selected path or null: put `aria-current="true"` on that row.
- `usePaneMode()` → true at expanded+ (list and detail side by side).
- `usePane()` → `{ inPane, close }` in a detail screen. In a pane: hide Back (or show a quiet "x"
  at large+ that calls `close`), skip the shared header flight, pin the primary button to the pane,
  not the window. `close()` on the default is a no-op.
- `openInTab(tab, path)`: open a detail with that tab underneath, from anywhere (Today's Recent row
  → `openInTab("log", "/log/" + uid)`); it lands in Log's pane at expanded+.
- `[data-list-pane]` and `[data-detail-pane]` are the two scroll containers; each keeps its own
  scroll. Put `data-list-search` on the list's search `<input>` so `/` focuses it and Escape
  clears it.
- Resizing across 840 keeps the URL and all state: the selection moves between pane and layer
  with no animation, the list scrolls the selected row into view.

## 4. Sheets: `design/components/Sheet.tsx` (§2.4)

```tsx
<CoffeeSheet role="editor" label="Edit coffee" title="Edit coffee" dirty={changed}
  onSave={save} onClose={onClose} full>…</CoffeeSheet>
<CoffeeSheet role="picker" anchor={fieldRef} label="Grinder" onClose={…}>…</CoffeeSheet>
<ConfirmSheet title="Delete this recipe?" confirmText="Delete" danger onConfirm={del} onClose={…} />
```
| role | compact | medium | expanded+ | short (>= 600 wide) |
|---|---|---|---|---|
| `confirm` | bottom sheet | dialog 400 | dialog 420 | dialog 420 |
| `content` (default) | bottom sheet | bottom ≤ 640 | dialog 640 × ≤ 80vh | dialog 640 × 92vh |
| `editor` | full-height | full-height ≤ 640 | right side sheet 560 | side sheet |
| `picker` | sheet | sheet ≤ 640 | popover 480 × 560 at `anchor` (dialog 520 without) | dialog 520 |

Props: `role`, `anchor` (picker), `title` + `onSave` + `saveLabel` (the side sheet's sticky header
"Cancel / Save"; Ctrl/Cmd+S calls `onSave` in any presentation), `dirty` (scrim, Escape and drag ask
"Discard changes?" with "Keep editing" focused; body copy `discardBody`, default the coffee copy in
`DISCARD_COPY`), `initialFocus`, `align="top"` (palette), `full` (own history entry, as before).
Inside a sheet: `useSheetClose()` closes it with its animation (instead of dispatching `cancel`),
`useSheetPresentation()` tells you the presentation (hide your own Save when it is `"side"` and you
passed `onSave`). Pure table: `presentationFor(role, windowClass, { anchored, full })`.

`ConfirmSheet` (also still exported from `@/viz`): the **safe action gets focus** (A6). Safe =
`safe` prop, else Cancel when the action is `danger` or the cancel label is the default "Cancel",
else the action (today's "Leave this brew?" passes "Keep it going" as `confirmText` and "Discard"
as `cancelText`, so "Keep it going" is focused). Prefer stating it: `safe="confirm"`.

## 5. Command palette: `app/palette/` (§2.8)

```ts
import { registerCommands } from "@/app/palette/commands";
useEffect(() => registerCommands(() => next ? [{
  id: "brew.next", title: `Brew ${next.name}`, group: "Actions", icon: "Play", keys: "b",
  run: () => nav.push(`/brew/${next.id}`),
}] : []), [next]);
```
`Command { id, title, subtitle?, group: "Actions"|"Recipes"|"Coffees"|"Grinders"|"Brews"|"Go to",
icon?, keys?, keywords?, popularity?, run, secondary? }`. Sources are read when the palette opens;
the newest source wins an id. `runCommand(id)`, `findCommand(id)`. The shell already lists places,
Gear sections, shortcuts, theme, °C/°F, g/oz, sidebar, and searches recipes (Ctrl/Cmd+Enter
brews), coffees, grinders and brews. Open it from a compact header icon with `openPalette()`
(`app/shellUi.ts`); `openShortcuts()` opens the `?` dialog.

## 6. Shortcuts: `app/shortcuts.ts` (§2.7)

```ts
import { useShortcuts } from "@/app/shortcuts";
const keys = useMemo(() => [
  { keys: "space", label: "Play or pause", group: "Brewing", run: toggle },
  { keys: "left", label: "Back 5 seconds", group: "Brewing", run: () => seek(-5) },
  { keys: "escape", label: "Leave this brew", group: "Brewing", run: askLeave },
], [toggle, seek, askLeave]);
useShortcuts("brew", keys, running);
```
`Binding { keys, label, group, run, whileTyping?, inDialog?, hidden?, display? }`. Spelling:
`"mod+k"` (Ctrl or Cmd), `"shift+d"`, `"g t"` (chord, 1 s), `"escape"`, `"space"`, `"left"`,
`"delete"`, `"?"`, `"["`. The newest scope wins, so a screen's `b` or Escape beats the shell's.
Never while typing (except Ctrl/Cmd combos and Escape, or `whileTyping`); only `inDialog`
bindings while a `<dialog>` is open; nothing while focus is in a menu or popover. Groups used by
the `?` dialog: General, Navigation, Lists, Brewing, Review, Editing. The shell owns Ctrl/Cmd+K,
`/`, `?`, 1-5, G T/R/L/I/S, `[`, Ctrl/Cmd+\, B (Start brew) and Escape (clear the list search, else
blur the field, else Back).

## 7. Primitives: `@/design/components`

- **Tooltip** (A10): `IconWell` already has one (its `label`, plus `keys="space"`). Anything else:
  `<button {...tooltipProps("Pause", "space")} aria-label="Pause">` or
  `<Tooltip label="Pause" keys="space"><button …/></Tooltip>`. 500 ms hover, at once on keyboard
  focus; the shortcut goes to `aria-keyshortcuts`, never the name. No `title` attributes.
- **Kbd**: `<Kbd keys="mod+k" />` → "Ctrl K" / "⌘K". `formatKeys`, `keyParts` in `design/keys.ts`.
- **ContextMenu**:
  ```tsx
  const menu = useContextMenu(() => [
    { label: "Brew", icon: "Play", keys: "b", onSelect: brew },
    { label: "Delete", icon: "Delete", keys: "delete", danger: true, onSelect: askDelete },
  ], `Actions for ${recipe.name}`);
  <div className="interactive-row" onContextMenu={menu.onContextMenu}>
    … <MenuButton className="row-more" label={`More for ${recipe.name}`} onOpen={menu.openFrom} />
  </div>
  {menu.element}
  ```
- **Term** (§4.5): `<Term id="ratio">RATIO</Term>`, `<Term id="bloom" chip />`,
  `<Term id="drawdown" hint={experience === "new" && first} />`. Popover at expanded+, content sheet
  below. Text from `shared/glossary.json` (loaded on first open). `TermBody` renders a term anywhere.
- **useRovingList** (§2.6):
  ```tsx
  const list = useRovingList({ count: rows.length, selectedIndex, onSettle: paneMode ? (i) => select(pathOf(rows[i])) : undefined });
  {rows.map((r, i) => <button key={r.id} {...list.itemProps(i)} className="interactive-row" aria-current={i === selectedIndex || undefined} onClick={() => select(pathOf(r))}>…</button>)}
  ```
  Tab enters once; Up/Down and J/K move, Home/End jump, Enter opens (`onOpen`, or the row's own
  click); `onSettle` fires after 120 ms of rest. Pure step: `rovingNext(key, i, count)`.
- **SegmentedControl** is now a radio group (roving tabindex, arrows select). Pass
  `panelIds={["gear-beans", …]}` only for real tabs (Gear sections): it becomes a tablist with
  `aria-controls`. **DotRating**: arrows, Home/End.
- **Classes** (`design/base.css`): `interactive-card` (hover `--surface1 → --surface2`;
  `data-lift` adds the 1 px rise, recipe grid cards only), `interactive-row` (hover `--bg →
  --surface1`; `aria-current` → `--surface2`), `row-more` (shows on row hover/focus), `[data-title]`
  goes `--ink-high` on hover, `.measure`, `.skip-link`. Focus ring everywhere: 2 px `--ink-high`,
  offset 2, follows the radius. Forced colors: rows, chips, segments, dots, nav handled.

## 8. Accessibility the shell does for you (§5)
- `document.title` = "<screen> · Coffee" (recipe names, "Brew <recipe>", "Settings"…).
- After a push or a tab switch, focus goes to the new screen's **first `h1`** (give every screen
  one; a pane screen's `h1` inside `[data-detail-pane]` is what "Skip to content" lands on), or the
  layer itself; Back/Escape returns focus to what opened the layer. Pane selections leave focus in
  the list.
- Detail layers are `<main>` with human labels ("Recipe", "Brew details", "Rate this brew"…);
  tabs are `<main id="main">`. "Skip to content" is the first Tab stop.

## 9. Toasts: `app/toast.tsx` (§3.14)
`showToast({ text: "Deleted", action: { label: "Undo", run: undo } })` → dismiss function. Above
the bar on compact; bottom-centre of the content area, ≤ 480, from medium up.

## 10. Routes added
`compare` `/log/:uid/compare/:other` (pane-able in Log; stub `features/log/CompareScreen.tsx` for
V4-WLOG to replace) and `brew` with `againUid` `/brew/:recipeId/again/:brewUid` (BrewScreen gets an
optional `againUid` prop; V4-WBREW adds it to its signature).

## 11. Motion rules (§2.9)
Transform, opacity and background-color only. Pane change: the old detail goes at once, the new
one fades with an 8 px drift (opacity only when reduced). Breakpoint crossings never animate.
Sheets: dialog scale 0.96 → 1 + fade; side sheet 24 px slide + fade; popover 4 px drop + fade.
