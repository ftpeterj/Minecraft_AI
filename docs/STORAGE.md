# AIBots storage network

Crew bots deposit loot only into the **registered storage network**
(`plugins/AIBots/storage.yml`), not every chest you place by hand.

## Register a room of chests (like `/fill`)

Two opposite corners define a **solid 3D box**. Every block inside is checked for:

- chest / trapped chest (including each half of a double chest)
- barrel
- shulker box

Matching containers are **linked** (not destroyed or emptied).

### Option A — coordinates (most precise)

```
/crew storage clear
/crew storage register <x1> <y1> <z1> <x2> <y2> <z2>
/crew storage list
```

Same geometry as:

```
/fill <x1> <y1> <z1> <x2> <y2> <z2> …
```

### Option B — look at corners (`pos1` / `pos2`)

```
/crew storage pos1      # look at one corner block
/crew storage pos2      # look at the opposite corner
/crew storage register
/crew storage list
```

## ⚠️ Critical detail (pos1 / pos2)

**Both corners must span the full height of the room.**

If you set both corners on the **same floor Y**, the box is only ~1 block tall.
Wall chests and upper shelves are **outside** the box and will not register.

### What works well (confirmed in play)

1. **pos1** — look at a **floor** corner of the room (or a lower chest corner).  
2. **pos2** — look at the **opposite** corner **higher up** (ceiling / top of chest wall).  
3. Confirm chat shows a box size with **height ≥ 3** (e.g. `30×8×40`, not `30×1×40`).  
4. `/crew storage register` — check **Found: N** matches roughly how many chests you see.

### If the selection is too flat

From **1.5.6+**, a flat box auto-expands height slightly (`register-y-pad-*` in `config.yml`)
and chat says *“Height was auto-expanded”*. Prefer explicit floor + ceiling corners anyway
for a clean room bound.

## After register

```
/crew deposit <bot>     # force bag → network now
/crew has spruce        # stock check
/crew storage list      # list network + free slots
/crew storage <#>       # contents of one unit
```

**Note:** `/crew say <bot> deposit…` only talks to the LLM — it does **not** move items.
Use `/crew deposit <bot>` or wait until the bag is full while gathering.

## Double chests

A double chest is **two blocks**, **one inventory**.  
`Found` (block count) can be higher than unique inventories — that is expected.

## Config knobs (`plugins/AIBots/config.yml`)

```yaml
storage:
  register-max-volume: 200000      # max blocks in one register scan
  register-min-y-thickness: 3      # thinner than this → auto Y pad
  register-y-pad-below: 1
  register-y-pad-above: 8
```

## Work radius (`/crew radius`)

Bots search for resources within a **work radius** (default 48; was hard-capped at 48 historically).

```
/crew radius                 # show effective / default / session
/crew radius 80              # this session only
/crew radius default 80      # save to config.yml
/crew radius 250 confirm     # required if > 200 (warns about lag)
/crew radius clear           # drop session override
```

## Rail + chest minecart haul (v1)

When deposit target is far (`crew.rail-haul.min-distance`, default 40) and the bot has
**rails** (and ideally a **chest minecart**) in bag or storage, gatherers will:

1. Pull rails/cart from the network if needed  
2. Lay rails toward storage  
3. Load a chest minecart and push it while walking  

Stock rails in the registered chest room for long desert/forest hauls.

## Related files

- `storage.yml` — registered chest locations + hub  
- Never re-place over full chests (plugin avoids air-wipe when items present)
