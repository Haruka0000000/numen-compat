    // ------------------------------------------------------------------ 升级位：装、卸、过滤表

    /** 升级位上那一件升级；空格子返回 null。 */
    private static ItemStack upgradeAt(UpgradeHandler handler, int slot) {
        if (slot < 0 || slot >= handler.getSlots()) {
            return null;
        }
        ItemStack stack = handler.getStackInSlot(slot);
        return stack.m_41619_() ? null : stack;
    }

    /**
     * 这个升级位的过滤表；没有过滤表（比如堆叠升级）就返回 null。
     *
     * <p>拾取、磁铁、虚空、喂食这些升级都是 {@code IFilteredUpgrade}，它们的过滤表自带存盘
     * （写入过滤格会顺手把 NBT 写回升级物品），所以这里拿到的就是活的表。
     */
    private static FilterLogic filterOf(UpgradeHandler handler, int slot) {
        if (upgradeAt(handler, slot) == null) {
            return null;
        }
        try {
            Map<Integer, IUpgradeWrapper> wrappers = handler.getSlotWrappers();
            IUpgradeWrapper wrapper = wrappers == null ? null : wrappers.get(slot);
            if (wrapper instanceof IFilteredUpgrade filtered) {
                return filtered.getFilterLogic();
            }
        } catch (Throwable ignored) {
            // 包装器还没建起来，就当这个格子没有过滤表。
        }
        return null;
    }

    /** 点名的升级位，或者第一个带过滤表的升级。 */
    private static int filterSlot(Carried c, Optional<Integer> asked) {
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        int slots = handler.getSlots();
        String bag = "背包";
        if (asked.isPresent()) {
            int slot = asked.get();
            if (slot < 0 || slot >= slots) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "升级位只有 " + slots + " 个，没有第 " + slot + " 个",
                        "格号从 0 数起；sb.back.upgrades() 列出每个升级所在的格号。", null);
            }
            if (filterOf(handler, slot) == null) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "第 " + slot + " 个升级位上没有能设过滤的升级",
                        "只有拾取、磁铁、虚空、喂食这类带过滤表的升级才有白黑名单；sb.back.filter() 看哪些有。", null);
            }
            return slot;
        }
        for (int slot = 0; slot < slots; slot++) {
            if (filterOf(handler, slot) != null) {
                return slot;
            }
        }
        throw new ApiError(ErrorKind.NOT_FOUND, bag + "上没有能设过滤的升级",
                "先装一个拾取升级或磁铁升级：sb.back.upgrade_put(\"sophisticatedbackpacks:pickup_upgrade\")。", null);
    }

    private static boolean truthy(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        return v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1") || v.equals("开")
                || v.equals("是") || v.equals("匹配");
    }

    /** 从 TagKey 的 toString（{@code TagKey[minecraft:item / minecraft:logs]}）里取出名字。 */
    private static String tagName(Object key) {
        String text = String.valueOf(key);
        int slash = text.lastIndexOf('/');
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        text = text.replace("]", "").trim();
        return text.endsWith(" ") ? text.trim() : text;
    }

    private static double dist2(BlockPos a, BlockPos b) {
        double dx = a.m_123341_() - b.m_123341_();
        double dy = a.m_123342_() - b.m_123342_();
        double dz = a.m_123343_() - b.m_123343_();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    @Fn("把她身上的一块升级装进背包的升级位。")
    @Example("sb.back.upgrade_put(\"sophisticatedbackpacks:pickup_upgrade\")")
    @Example("sb.back.upgrade_put(\"sophisticatedbackpacks:magnet_upgrade\", {which = 2})")
    @Note("升级得先在她身上（物品栏里）。装进第一个空着、又肯收它的升级位；装完才从她身上扣。")
    @SeeAlso({"sb.back.upgrades", "sb.back.upgrade_take", "sb.back.filter"})
    public static Moved upgrade_put(ServerCall call, UpArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        String bag = displayName(her, c);
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        Inventory mine = her.m_150109_();
        int from = -1;
        for (int i = 0; i < mine.m_6643_(); i++) {
            ItemStack stack = mine.m_8020_(i);
            if (!stack.m_41619_() && stack.m_150930_(item) && !(stack.m_41720_() instanceof BackpackItem)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她身上没有" + name,
                    "先把升级放进她的物品栏，再 sb.back.upgrade_put 装上去。", null);
        }
        ItemStack one = mine.m_8020_(from).m_41777_();
        one.m_41764_(1);
        int target = -1;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).m_41619_()) {
                continue;
            }
            if (!handler.isItemValid(slot, one)) {
                continue;
            }
            target = slot;
            break;
        }
        if (target < 0) {
            throw new ApiError(ErrorKind.FAILED, bag + "的升级位满了，或者不收" + name,
                    "先把用不上的升级拿下来：sb.back.upgrade_take(0)。", null);
        }
        ItemStack rest = handler.insertItem(target, one, false);
        if (!rest.m_41619_()) {
            throw new ApiError(ErrorKind.FAILED, bag + "把" + name + "退回来了", "这个升级位不收它。", null);
        }
        ItemStack inHand = mine.m_8020_(from);
        int left = inHand.m_41613_() - 1;
        if (left <= 0) {
            mine.m_6836_(from, ItemStack.f_41583_);
        } else {
            ItemStack shrunk = inHand.m_41777_();
            shrunk.m_41764_(left);
            mine.m_6836_(from, shrunk);
        }
        return new Moved(1, countIn(handler, item), Optional.empty(),
                "把" + name + "装进了" + bag + "的第 " + target + " 个升级位");
    }

    @Fn("把背包升级位上的一块升级拿下来，放进她身上。")
    @Example("sb.back.upgrade_take(0)")
    @Example("sb.back.upgrade_take(1, {which = 2})")
    @Note("格号从 0 数起，sb.back.upgrades() 里每个升级的 slot 就是它。她身上放不下时会把升级还回原格。")
    @SeeAlso({"sb.back.upgrades", "sb.back.upgrade_put"})
    public static Moved upgrade_take(ServerCall call, SlotArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        String bag = displayName(her, c);
        int slot = args.slot();
        ItemStack up = handler.extractItem(slot, 1, false);
        if (up.m_41619_()) {
            throw new ApiError(ErrorKind.NOT_FOUND, bag + "的第 " + slot + " 个升级位是空的",
                    "sb.back.upgrades() 列出装着升级的格号。", null);
        }
        if (!her.m_150109_().m_36054_(up)) {
            handler.insertItem(slot, up, false);
            throw new ApiError(ErrorKind.FAILED, "她身上放不下" + label(up),
                    "腾出一格再来；升级还留在原来的升级位里。", null);
        }
        return new Moved(1, 0, Optional.empty(),
                "把" + label(up) + "从" + bag + "的第 " + slot + " 个升级位拿下来，放进她身上了");
    }

    @Fn("看背包上每个带过滤表的升级：是白名单还是黑名单、过滤了哪些东西、匹配什么。")
    @Example("for _, f in ipairs(sb.back.filter().filters) do print(f.slot, f.name, f.mode) end")
    @Example("local fs = sb.back.filter({which = 2}).filters")
    @Note("只读。拾取、磁铁、虚空、喂食这些升级的白黑名单都在这里；堆叠升级这类没有过滤表的不会出现。")
    @SeeAlso({"sb.back.upgrades", "sb.back.filter_add", "sb.back.filter_set"})
    public static Filters filter(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        String bag = displayName(her, c);
        List<Filter> out = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            FilterLogic logic = filterOf(handler, slot);
            if (logic == null) {
                continue;
            }
            ItemStack up = upgradeAt(handler, slot);
            FilterItemStackHandler table = logic.getFilterHandler();
            List<FilterItem> items = new ArrayList<>();
            for (int i = 0; i < table.getSlots(); i++) {
                ItemStack stack = table.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                items.add(new FilterItem(i, idOf(stack), label(stack)));
            }
            List<String> tags = new ArrayList<>();
            try {
                for (TagKey<Item> key : logic.getTagKeys()) {
                    tags.add(tagName(key));
                }
            } catch (Throwable ignored) {
                // 老版本没有标签列表，空着就好。
            }
            boolean allow = logic.isAllowList();
            String primary = "item";
            try {
                PrimaryMatch match = logic.getPrimaryMatch();
                primary = match == null ? "item" : match.m_7912_();
            } catch (Throwable ignored) {
                // 没有主匹配就用 item。
            }
            String name = up == null ? "升级" : label(up);
            String line = bag + "的第 " + slot + " 个升级位（" + name + "）是"
                    + (allow ? "白名单：只收表里的东西" : "黑名单：只挡表里的东西")
                    + "，表里 " + items.size() + " 项东西"
                    + (tags.isEmpty() ? "" : "、" + tags.size() + " 个标签");
            out.add(new Filter(slot, up == null ? "minecraft:air" : idOf(up), name, allow ? "allow" : "deny",
                    table.getSlots(), items, tags, primary, logic.shouldMatchNbt(), logic.shouldMatchDurability(),
                    line));
        }
        String line = out.isEmpty()
                ? bag + "上没有能设过滤的升级"
                : bag + "上有 " + out.size() + " 个带过滤表的升级";
        return new Filters(out, line);
    }

    @Fn("把一样东西加进升级的过滤表（白名单就是「收它」，黑名单就是「挡它」）。")
    @Example("sb.back.filter_add(\"minecraft:rotten_flesh\")")
    @Example("sb.back.filter_add(\"minecraft:diamond\", {slot = 1})")
    @Note("只加一项，加到第一个空格子里；表满了会说。改白/黑用 sb.back.filter_set。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_remove", "sb.back.filter_set"})
    public static Moved filter_add(ServerCall call, FilterArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        Item item = itemOf(args.item());
        ItemStack one = new ItemStack(item, 1);
        String name = label(one);
        for (int i = 0; i < table.getSlots(); i++) {
            ItemStack have = table.getStackInSlot(i);
            if (!have.m_41619_() && have.m_150930_(item)) {
                return new Moved(0, 0, Optional.of(name + "已经在过滤表里了"),
                        "过滤表里已经有" + name + "了，什么都没改");
            }
        }
        int free = -1;
        for (int i = 0; i < table.getSlots(); i++) {
            if (table.getStackInSlot(i).m_41619_()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            throw new ApiError(ErrorKind.FAILED, "过滤表的 " + table.getSlots() + " 格都满了",
                    "先用 sb.back.filter_remove(\"物品id\") 拿掉几项。", null);
        }
        ItemStack rest = table.insertItem(free, one, false);
        if (!rest.m_41619_()) {
            throw new ApiError(ErrorKind.FAILED, "过滤表的这一格不收" + name, "试试别的格子或者别的物品。", null);
        }
        return new Moved(1, 0, Optional.empty(),
                "把" + name + "加进了第 " + slot + " 个升级的过滤表（第 " + free + " 格）");
    }

    @Fn("把一样东西从升级的过滤表里拿掉。")
    @Example("sb.back.filter_remove(\"minecraft:rotten_flesh\")")
    @Note("表里没有它就说没找到，什么都不改。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add", "sb.back.filter_clear"})
    public static Moved filter_remove(ServerCall call, FilterArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        int cleared = 0;
        for (int i = 0; i < table.getSlots(); i++) {
            ItemStack have = table.getStackInSlot(i);
            if (!have.m_41619_() && have.m_150930_(item)) {
                table.setStackInSlot(i, ItemStack.f_41583_);
                cleared++;
            }
        }
        if (cleared == 0) {
            return new Moved(0, 0, Optional.of(name + "不在过滤表里"), "过滤表里没有" + name + "，什么都没改");
        }
        return new Moved(cleared, 0, Optional.empty(),
                "把" + name + "从第 " + slot + " 个升级的过滤表里拿掉了");
    }

    @Fn("把升级的过滤表整个清空。")
    @Example("sb.back.filter_clear(0)")
    @Note("清空之后白名单会变成「什么都收」，黑名单会变成「什么都不挡」。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add"})
    public static Moved filter_clear(ServerCall call, SlotArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        int slot = filterSlot(c, Optional.of(args.slot()));
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        int cleared = 0;
        for (int i = 0; i < table.getSlots(); i++) {
            if (!table.getStackInSlot(i).m_41619_()) {
                table.setStackInSlot(i, ItemStack.f_41583_);
                cleared++;
            }
        }
        return new Moved(cleared, 0, Optional.empty(),
                "把第 " + slot + " 个升级的过滤表清空了（拿掉 " + cleared + " 项）");
    }

    @Fn("改升级的过滤设置：白名单还是黑名单、匹配什么、要不要看 NBT 和耐久。")
    @Example("sb.back.filter_set(\"mode\", \"allow\")")
    @Example("sb.back.filter_set(\"primary\", \"tags\")")
    @Example("sb.back.filter_set(\"nbt\", \"true\", {slot = 1})")
    @Note("key 只能是 mode（allow / deny）、primary（item / mod / tags）、nbt、durability、any_tag（true / false）。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add", "sb.back.filter_clear"})
    public static Moved filter_set(ServerCall call, SetArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = c.wrapper().getUpgradeHandler();
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        String key = args.key() == null ? "" : args.key().trim().toLowerCase();
        String value = args.value() == null ? "" : args.value().trim();
        String what;
        switch (key) {
            case "mode":
            case "allow":
            case "list": {
                String v = value.toLowerCase();
                boolean allow;
                if (v.startsWith("allow") || v.startsWith("white") || v.equals("白") || v.equals("白名单")
                        || v.equals("收")) {
                    allow = true;
                } else if (v.startsWith("deny") || v.startsWith("block") || v.equals("黑") || v.equals("黑名单")
                        || v.equals("挡")) {
                    allow = false;
                } else {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "mode 只认 allow 或 deny，写的是：" + value,
                            "allow = 白名单（只收表里的），deny = 黑名单（只挡表里的）。", null);
                }
                logic.setAllowList(allow);
                what = allow ? "白名单：只收表里的东西" : "黑名单：只挡表里的东西";
                break;
            }
            case "primary":
            case "match": {
                PrimaryMatch match = PrimaryMatch.fromName(value.toLowerCase());
                if (match == null) {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "primary 只认 item、mod、tags，写的是：" + value,
                            "item 按单个物品比，mod 按模组比，tags 按标签比。", null);
                }
                logic.setPrimaryMatch(match);
                what = "按 " + match.m_7912_() + " 匹配";
                break;
            }
            case "nbt": {
                boolean on = truthy(value);
                logic.setMatchNbt(on);
                what = on ? "匹配时也看 NBT" : "匹配时不看 NBT";
                break;
            }
            case "durability": {
                boolean on = truthy(value);
                logic.setMatchDurability(on);
                what = on ? "匹配时也看耐久" : "匹配时不看耐久";
                break;
            }
            case "any_tag":
            case "tag": {
                boolean on = truthy(value);
                logic.setMatchAnyTag(on);
                what = on ? "任意一个标签对上就算" : "标签要全部对上";
                break;
            }
            default:
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "不认识的设置：" + key,
                        "key 只能是 mode、primary、nbt、durability、any_tag。", null);
        }
        return new Moved(1, 0, Optional.empty(),
                "第 " + slot + " 个升级的过滤设置改成：" + what);
    }

    @Fn("把背着的背包放到地上（变成方块），她身上会少一个背包。")
    @Example("sb.back.place()")
    @Example("sb.back.place({which = 2})")
    @Note("放在她面前那格的地上；面前没地方会说到处都不行。放下去之后背包里的东西原样在里面。")
    @SeeAlso({"sb.back.pickup", "sb.back.info"})
    public static Moved place(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        if (!(c.stack().m_41720_() instanceof BackpackItem backpack)) {
            throw new ApiError(ErrorKind.FAILED, "这一格拿的不是背包物品", "sb.back.packs() 看她在背什么。", null);
        }
        String bag = displayName(her, c);
        ServerLevel level = (ServerLevel) her.m_9236_();
        Direction facing = her.m_6350_();
        int dx = facing == Direction.EAST ? 1 : facing == Direction.WEST ? -1 : 0;
        int dz = facing == Direction.SOUTH ? 1 : facing == Direction.NORTH ? -1 : 0;
        if (dx == 0 && dz == 0) {
            dz = 1;
        }
        BlockPos at = her.m_20183_();
        int x = at.m_123341_();
        int y = at.m_123342_();
        int z = at.m_123343_();
        BlockPos spot = null;
        for (int step = 1; step <= 2 && spot == null; step++) {
            for (int up = 0; up <= 1 && spot == null; up++) {
                BlockPos floor = new BlockPos(x + dx * step, y + up - 1, z + dz * step);
                BlockPos target = new BlockPos(x + dx * step, y + up, z + dz * step);
                if (level.m_46749_(target) && level.m_8055_(target).m_60795_()
                        && !level.m_8055_(floor).m_60795_()) {
                    spot = target;
                }
            }
        }
        if (spot == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她面前没有能放背包的空地",
                    "往前走一步找块平地再放；或者先用 sb.back.open 打开窗口。", null);
        }
        BlockPos floor = new BlockPos(spot.m_123341_(), spot.m_123342_() - 1, spot.m_123343_());
        BlockHitResult hit = new BlockHitResult(
                new Vec3(spot.m_123341_() + 0.5, spot.m_123342_(), spot.m_123343_() + 0.5),
                Direction.UP, floor, false);
        BlockPlaceContext ctx = new BlockPlaceContext(her, InteractionHand.MAIN_HAND, c.stack(), hit);
        InteractionResult result = backpack.tryPlace(her, Direction.UP, ctx);
        if (result != InteractionResult.SUCCESS) {
            throw new ApiError(ErrorKind.FAILED, "这个位置放不下" + bag,
                    "换个地方试，或者先清掉那格的东西。", null);
        }
        return new Moved(1, 0, Optional.empty(), "把" + bag + "放在了 " + coord(spot));
    }

    @Fn("把地上的背包捡回她身上。")
    @Example("sb.back.pickup()")
    @Example("sb.back.pickup({radius = 12})")
    @Note("这就是玩家蹲下右键那一下。它装了限制管理员拿取的无限升级时会拒绝。")
    @SeeAlso({"sb.back.place", "sb.back.packs"})
    public static Moved pickup(ServerCall call, RadiusOpt args) {
        ServerPlayer her = call.her();
        ServerLevel level = (ServerLevel) her.m_9236_();
        int radius = Math.min(16, Math.max(1, args.radius().orElse(6)));
        BlockPos at = her.m_20183_();
        int x = at.m_123341_();
        int y = at.m_123342_();
        int z = at.m_123343_();
        BlockPos found = null;
        BackpackBlockEntity target = null;
        double best = Double.MAX_VALUE;
        int cx0 = (x - radius) >> 4;
        int cx1 = (x + radius) >> 4;
        int cz0 = (z - radius) >> 4;
        int cz1 = (z + radius) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.m_46749_(new BlockPos(cx << 4, y, cz << 4))) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                    if (!(entry.getValue() instanceof BackpackBlockEntity candidate)) {
                        continue;
                    }
                    double d = dist2(entry.getKey(), at);
                    if (d > (double) radius * radius || d >= best) {
                        continue;
                    }
                    best = d;
                    found = entry.getKey();
                    target = candidate;
                }
            }
        }
        if (found == null || target == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她 " + radius + " 格内没有地上的背包",
                    "走过去再捡，或者把 radius 调大一点（最多 16）。", null);
        }
        if (!mayPickup(her, found)) {
            throw new ApiError(ErrorKind.DENIED, "这个地上的背包不让她捡",
                    "它装了限制管理员拿取的无限升级，只有它的主人能捡。", null);
        }
        ItemStack back;
        try {
            back = target.getBackpackWrapper().getBackpack().m_41777_();
        } catch (Throwable bad) {
            throw new ApiError(ErrorKind.FAILED, "这个方块里读不出背包", "别动它，先把它周围清理干净再试。", null);
        }
        if (back.m_41619_()) {
            throw new ApiError(ErrorKind.FAILED, "这个方块里没有背包", "那可能是个空壳方块。", null);
        }
        if (!her.m_150109_().m_36054_(back)) {
            throw new ApiError(ErrorKind.FAILED, "她身上放不下这个背包", "腾出一格再来捡；背包还在地上。", null);
        }
        level.m_7471_(found, false);
        return new Moved(1, 0, Optional.empty(),
                "把地上的" + label(back) + "捡回了身上（原来在 " + coord(found) + "）");
    }

    /**
     * 模组自己的「能不能捡」判断：装了限制管理员的无限升级就只有主人能捡。
     * 这个方法是 private static，所以反射调；拿不到就当能捡（和模组默认一致）。
     */
    private static boolean mayPickup(ServerPlayer player, BlockPos pos) {
        try {
            Method method = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock")
                    .getDeclaredMethod("hasPermissionsToPickup", Player.class, BlockPos.class);
            method.setAccessible(true);
            Object answer = method.invoke(null, player, pos);
            return !(answer instanceof Boolean allowed) || allowed;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 新形状

    /** 装一块升级。 */
    public record UpArg(
            @Doc("升级的物品 id，比如 sophisticatedbackpacks:pickup_upgrade。") String item,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 点一个升级位。 */
    public record SlotArg(
            @Doc("升级位的格号，从 0 数起；sb.back.upgrades() 里每个升级的 slot 就是它。") int slot,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 往过滤表里加一样东西。 */
    public record FilterArg(
            @Doc("要加进过滤表的物品 id。") String item,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which,
            @Doc("升级位的格号；不写就是第一个带过滤表的升级。") @Omitted("第一个带过滤表的升级")
            Optional<Integer> slot) {
    }

    /** 改升级的过滤设置。 */
    public record SetArg(
            @Doc("改哪一项：mode、primary、nbt、durability、any_tag。") String key,
            @Doc("改成什么。") String value,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which,
            @Doc("升级位的格号；不写就是第一个带过滤表的升级。") @Omitted("第一个带过滤表的升级")
            Optional<Integer> slot) {
    }

    /** 捡地上的背包时扫多大范围。 */
    public record RadiusOpt(
            @Doc("以她为中心扫几格；不写就是 6。") @Omitted("6")
            Optional<Integer> radius) {
    }

    /** 过滤表里的一项。 */
    public record FilterItem(
            @Doc("过滤表里的格号。") int slot,
            @Doc("物品 id。") String item,
            @Doc("它的中文名。") String name) {
    }

    /** 一个带过滤表的升级。 */
    public record Filter(
            @Doc("它在升级位里的格号。") int slot,
            @Doc("升级的物品 id。") String item,
            @Doc("升级的中文名。") String name,
            @Doc("allow = 白名单（只收表里的），deny = 黑名单（只挡表里的）。") String mode,
            @Doc("过滤表一共有几格。") int slots,
            @Doc("表里过滤了哪些东西。") List<FilterItem> items,
            @Doc("表里的标签，比如 minecraft:logs。") List<String> tags,
            @Doc("主匹配方式：item、mod 或 tags。") String primary,
            @Doc("比的时候要不要看 NBT。") boolean match_nbt,
            @Doc("比的时候要不要看耐久。") boolean match_durability,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 背包上所有带过滤表的升级。 */
    public record Filters(
            @Doc("逐个列出带过滤表的升级。") List<Filter> filters,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }
