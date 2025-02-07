package io.github.flemmli97.simplequests.player;

import com.mojang.datafixers.util.Pair;
import io.github.flemmli97.simplequests.api.SimpleQuestAPI;
import io.github.flemmli97.simplequests.config.ConfigHandler;
import io.github.flemmli97.simplequests.datapack.QuestsManager;
import io.github.flemmli97.simplequests.quest.Quest;
import io.github.flemmli97.simplequests.quest.QuestEntry;
import io.github.flemmli97.simplequests.quest.QuestEntryImpls;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class PlayerData {

    public static final DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ServerPlayer player;
    private List<QuestProgress> currentQuests = new ArrayList<>();
    private Map<ResourceLocation, Long> cooldownTracker = new HashMap<>();
    private List<QuestProgress> tickables = new ArrayList<>();

    private Set<ResourceLocation> unlockTracker = new HashSet<>();

    private long resetTick = -1;

    private LocalDateTime questTrackerTime = LocalDateTime.now();
    private final Map<ResourceLocation, Integer> dailyQuestsTracker = new HashMap<>();

    private int interactionCooldown;

    public static PlayerData get(ServerPlayer player) {
        return ((SimpleQuestDataGet) player).simpleQuestPlayerData();
    }

    public PlayerData(ServerPlayer player) {
        this.player = player;
    }

    public boolean acceptQuest(Quest quest) {
        int maxConcurrent = quest.category.getMaxConcurrentQuests();
        if (maxConcurrent > 0 && this.currentQuests.stream()
                .filter(p -> !p.getQuest().isDailyQuest && !quest.category.sameCategoryOnly || p.getQuest().category == quest.category).toList().size() >= maxConcurrent) {
            this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.active.full")).withStyle(ChatFormatting.DARK_RED));
            return false;
        }
        if (this.isActive(quest)) {
            this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.active")).withStyle(ChatFormatting.DARK_RED));
            return false;
        }
        AcceptType type = this.canAcceptQuest(quest);
        if (type != AcceptType.ACCEPT) {
            if (type == AcceptType.DELAY)
                this.player.sendSystemMessage(Component.translatable(String.format(ConfigHandler.lang.get(type.langKey()), this.formattedCooldown(quest))).withStyle(ChatFormatting.DARK_RED));
            else
                this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get(type.langKey())).withStyle(ChatFormatting.DARK_RED));
            return false;
        }
        this.currentQuests.add(new QuestProgress(quest, this));
        this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.accept"), quest.getFormatted(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN));
        return true;
    }

    public boolean submit(String trigger, boolean sendFailMessage) {
        if (this.currentQuests.isEmpty()) {
            if (sendFailMessage)
                this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.current.no")).withStyle(ChatFormatting.DARK_RED));
            return false;
        }
        boolean any = false;
        List<QuestProgress> completed = new ArrayList<>();
        for (QuestProgress prog : this.currentQuests) {
            switch (prog.submit(this.player, trigger)) {
                case COMPLETE -> {
                    this.completeQuest(prog);
                    completed.add(prog);
                    any = true;
                }
                case PARTIAL -> this.player.level.playSound(null, this.player.getX(), this.player.getY(), this.player.getZ(), SoundEvents.VILLAGER_YES, this.player.getSoundSource(), 2 * 0.75f, 1.0f);
                case NOTHING -> {
                    if (sendFailMessage)
                        this.player.level.playSound(null, this.player.getX(), this.player.getY(), this.player.getZ(), SoundEvents.VILLAGER_NO, this.player.getSoundSource(), 2 * 0.75f, 1.0f);
                }
            }
        }
        this.currentQuests.removeAll(completed);
        return any;
    }

    public <T extends QuestEntry> void tryFullFill(Class<T> clss, SimpleQuestAPI.QuestEntryPredicate<T> pred, BiConsumer<QuestProgress, Pair<String, T>> onFullfill) {
        this.tryFullFill(clss, pred, onFullfill, "");
    }

    public <T extends QuestEntry> void tryFullFill(Class<T> clss, SimpleQuestAPI.QuestEntryPredicate<T> pred, BiConsumer<QuestProgress, Pair<String, T>> onFullfill, String trigger) {
        List<QuestProgress> completed = new ArrayList<>();
        this.currentQuests.forEach(prog -> {
            Set<Pair<String, T>> fulfilled = prog.tryFullFill(clss, pred);
            if (!fulfilled.isEmpty()) {
                this.player.level.playSound(null, this.player.getX(), this.player.getY(), this.player.getZ(), SoundEvents.PLAYER_LEVELUP, this.player.getSoundSource(), 2 * 0.75f, 1.0f);
                fulfilled.forEach(p -> onFullfill.accept(prog, p));
            }
            if (prog.isCompleted(trigger)) {
                this.completeQuest(prog);
                completed.add(prog);
            }
        });
        this.currentQuests.removeAll(completed);
    }

    public void onKill(LivingEntity entity) {
        this.tryFullFill(QuestEntryImpls.KillEntry.class, QuestProgress.createKillPredicate(this.player, entity),
                (prog, p) -> this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.kill"), p.getSecond().translation(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN)));
    }

    public void onInteractWith(Entity entity) {
        if (this.interactionCooldown > 0)
            return;
        this.interactionCooldown = 2;
        this.tryFullFill(QuestEntryImpls.EntityInteractEntry.class, QuestProgress.createInteractionPredicate(this.player, entity),
                (prog, p) -> this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.task"), p.getSecond().translation(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN)));
    }

    public void onBlockInteract(BlockPos pos, boolean use) {
        this.tryFullFill(QuestEntryImpls.BlockInteractEntry.class, QuestProgress.createBlockInteractionPredicate(this.player, pos, use),
                (prog, p) -> this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.task"), p.getSecond().translation(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN)));
    }

    public void onItemCrafted(ItemStack stack, int amount, String trigger) {
        this.tryFullFill(QuestEntryImpls.CraftingEntry.class, QuestProgress.createCraftingPredicate(this.player, stack, amount),
                (prog, p) -> this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.task"), p.getSecond().translation(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN)));
    }

    private void completeQuest(QuestProgress prog) {
        LootTable lootTable = this.player.getServer().getLootTables().get(prog.getQuest().loot);
        CriteriaTriggers.GENERATE_LOOT.trigger(this.player, prog.getQuest().loot);
        LootContext.Builder builder = new LootContext.Builder(this.player.getLevel())
                .withParameter(LootContextParams.ORIGIN, this.player.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, DamageSource.MAGIC)
                .withParameter(LootContextParams.THIS_ENTITY, this.player)
                .withLuck(this.player.getLuck());
        List<ItemStack> loot = lootTable.getRandomItems(builder.create(LootContextParamSets.ENTITY));
        loot.forEach(stack -> {
            boolean bl = this.player.getInventory().add(stack);
            if (!bl || !stack.isEmpty()) {
                ItemEntity itemEntity = this.player.drop(stack, false);
                if (itemEntity != null) {
                    itemEntity.setNoPickUpDelay();
                    itemEntity.setOwner(this.player.getUUID());
                }
            }
        });
        if (!prog.getQuest().command.isEmpty())
            this.player.getServer().getCommands().performPrefixedCommand(this.player.createCommandSourceStack(), prog.getQuest().command);
        this.cooldownTracker.put(prog.getQuest().id, this.player.level.getGameTime());
        this.unlockTracker.add(prog.getQuest().id);
        this.player.level.playSound(null, this.player.getX(), this.player.getY(), this.player.getZ(), SoundEvents.PLAYER_LEVELUP, this.player.getSoundSource(), 2 * 0.75f, 1.0f);
        this.player.sendSystemMessage(Component.translatable(String.format(ConfigHandler.lang.get("simplequests.finish"), prog.getQuest().getTask())).withStyle(ChatFormatting.DARK_GREEN));
        if (!prog.getQuest().neededParentQuests.isEmpty() && prog.getQuest().redoParent) {
            prog.getQuest().neededParentQuests.forEach(res -> {
                Quest quest = QuestsManager.instance().getAllQuests().get(res);
                if (quest != null)
                    this.unlockTracker.remove(quest.id);
            });
        }
    }

    public void reset(ResourceLocation res, boolean forced) {
        this.reset(res, forced, true);
    }

    public void reset(ResourceLocation res, boolean forced, boolean sendMsg) {
        if (this.currentQuests.isEmpty()) {
            if (sendMsg)
                this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.current.no")).withStyle(ChatFormatting.DARK_RED));
            return;
        }
        QuestProgress prog = null;
        for (QuestProgress p : this.currentQuests) {
            if (p.getQuest().id.equals(res)) {
                prog = p;
                break;
            }
        }
        if (prog == null) {
            if (sendMsg)
                this.player.sendSystemMessage(Component.translatable(String.format(ConfigHandler.lang.get("simplequests.reset.notfound"), res)).withStyle(ChatFormatting.DARK_RED));
            return;
        }
        if (!forced && this.resetTick == -1) {
            this.resetTick = this.player.level.getGameTime();
            if (sendMsg)
                this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.reset.confirm")).withStyle(ChatFormatting.DARK_RED));
            return;
        } else if (forced || this.player.level.getGameTime() - this.resetTick < 600) {
            if (sendMsg)
                this.player.sendSystemMessage(Component.translatable(String.format(ConfigHandler.lang.get("simplequests.reset"), prog.getQuest().getTask())).withStyle(ChatFormatting.DARK_RED));
            this.currentQuests.remove(prog);
        }
        this.resetTick = -1;
    }

    public List<QuestProgress> getCurrentQuest() {
        return this.currentQuests;
    }

    public boolean isActive(Quest quest) {
        return this.currentQuests.stream().anyMatch(prog -> prog.getQuest().id.equals(quest.id));
    }

    public AcceptType canAcceptQuest(Quest quest) {
        if (quest.isDailyQuest || quest.needsUnlock && !this.unlockTracker.contains(quest.id)) {
            return AcceptType.LOCKED;
        }
        if (!quest.unlockCondition.matches(this.player, this.player)
                || (!quest.neededParentQuests.isEmpty() && !this.unlockTracker.containsAll(quest.neededParentQuests))) {
            return AcceptType.REQUIREMENTS;
        }
        if (quest.repeatDaily > 0 && this.dailyQuestsTracker.getOrDefault(quest.id, 0) >= quest.repeatDaily)
            return AcceptType.DAILYFULL;
        //One time quests
        if (quest.repeatDelay < 0 && this.cooldownTracker.containsKey(quest.id))
            return AcceptType.ONETIME;
        if (this.cooldownTracker.containsKey(quest.id)) {
            return (quest.repeatDelay == 0 || Math.abs(this.player.level.getGameTime() - this.cooldownTracker.get(quest.id)) > quest.repeatDelay) ? AcceptType.ACCEPT : AcceptType.DELAY;
        }
        return AcceptType.ACCEPT;
    }

    public ServerPlayer getPlayer() {
        return this.player;
    }

    public void addTickableProgress(QuestProgress progress) {
        if (!this.tickables.contains(progress))
            this.tickables.add(progress);
    }

    public void removeQuestProgress(QuestProgress progress) {
        this.tickables.remove(progress);
    }

    public void unlockQuest(ResourceLocation quest) {
        this.unlockTracker.add(quest);
    }

    public void lockQuest(ResourceLocation quest) {
        this.unlockTracker.remove(quest);
    }

    public void tick() {
        --this.interactionCooldown;
        List<QuestProgress> completed = new ArrayList<>();
        this.tickables.removeIf(prog -> {
            Pair<Boolean, Set<QuestEntry>> fulfilled = prog.tickProgress(this);
            if (!fulfilled.getSecond().isEmpty()) {
                this.player.level.playSound(null, this.player.getX(), this.player.getY(), this.player.getZ(), SoundEvents.PLAYER_LEVELUP, this.player.getSoundSource(), 2 * 0.75f, 1.0f);
                fulfilled.getSecond().forEach(e -> this.player.sendSystemMessage(Component.translatable(ConfigHandler.lang.get("simplequests.task"), e.translation(this.player.getServer())).withStyle(ChatFormatting.DARK_GREEN)));
            }
            if (prog.isCompleted("")) {
                this.completeQuest(prog);
                completed.add(prog);
                return true;
            }
            return fulfilled.getFirst();
        });
        this.currentQuests.removeAll(completed);

        LocalDateTime now = LocalDateTime.now();
        if (this.questTrackerTime == null || this.questTrackerTime.getDayOfYear() != now.getDayOfYear()) {
            this.questTrackerTime = now;
            this.dailyQuestsTracker.forEach((r, i) -> {
                Quest quest = QuestsManager.instance().getAllQuests().get(r);
                if (quest != null && quest.isDailyQuest)
                    this.reset(r, true, false);
            });
            this.dailyQuestsTracker.clear();
            QuestsManager.instance().getDailyQuests().forEach(quest -> {
                this.currentQuests.add(new QuestProgress(quest, this));
                this.dailyQuestsTracker.put(quest.id, 1);
            });
        }
    }

    public String formattedCooldown(Quest quest) {
        long sec = Math.max(0, quest.repeatDelay - Math.abs(this.player.level.getGameTime() - this.cooldownTracker.get(quest.id))) / 20;
        if (sec > 86400) {
            long days = sec / 86400;
            long hours = (sec % 86400) / 3600;
            return String.format("%dd:%dh", days, hours);
        }
        if (sec >= 3600) {
            long hours = sec / 3600;
            long minutes = (sec % 3600) / 60;
            return String.format("%dh:%dm:%ds", hours, minutes, sec % 60);
        }
        if (sec >= 60) {
            long minutes = sec / 60;
            return String.format("%dm:%ds", minutes, sec % 60);
        }
        return String.format("%ds", sec);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag quests = new ListTag();
        this.currentQuests.forEach(prog -> quests.add(prog.save()));
        tag.put("ActiveQuests", quests);
        CompoundTag list = new CompoundTag();
        this.cooldownTracker.forEach((res, time) -> list.putLong(res.toString(), time));
        tag.put("FinishedQuests", list);
        if (this.questTrackerTime != null)
            tag.putString("TimeTracker", this.questTrackerTime.format(time));
        CompoundTag daily = new CompoundTag();
        this.dailyQuestsTracker.forEach((res, amount) -> daily.putInt(res.toString(), amount));
        tag.put("DailyQuestTracker", daily);
        ListTag unlocked = new ListTag();
        this.unlockTracker.forEach(res -> unlocked.add(StringTag.valueOf(res.toString())));
        tag.put("UnlockedQuests", unlocked);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.contains("ActiveQuests")) {
            ListTag quests = tag.getList("ActiveQuests", Tag.TAG_COMPOUND);
            quests.forEach(q -> {
                try {
                    QuestProgress prog = new QuestProgress((CompoundTag) q, this);
                    if (prog.getQuest() != null)
                        this.currentQuests.add(prog);
                } catch (IllegalStateException ignored) {
                }
            });
        }
        CompoundTag done = tag.getCompound("FinishedQuests");
        done.getAllKeys().forEach(key -> this.cooldownTracker.put(new ResourceLocation(key), done.getLong(key)));
        if (tag.contains("TimeTracker"))
            this.questTrackerTime = LocalDateTime.parse(tag.getString("TimeTracker"), time);
        CompoundTag daily = tag.getCompound("DailyQuestTracker");
        daily.getAllKeys().forEach(key -> this.dailyQuestsTracker.put(new ResourceLocation(key), done.getInt(key)));
        ListTag unlocked = tag.getList("UnlockedQuests", Tag.TAG_STRING);
        unlocked.forEach(t -> this.unlockTracker.add(new ResourceLocation(t.getAsString())));
    }

    public void clone(PlayerData data) {
        this.currentQuests = data.currentQuests;
        this.cooldownTracker = data.cooldownTracker;
        this.unlockTracker = data.unlockTracker;
    }

    public void resetAll() {
        this.currentQuests.clear();
        this.cooldownTracker.clear();
        this.unlockTracker.clear();
        this.questTrackerTime = null;
        this.dailyQuestsTracker.clear();
    }

    public void resetCooldown() {
        this.cooldownTracker.replaceAll((res, old) -> Long.MIN_VALUE);
    }

    public enum AcceptType {

        REQUIREMENTS("simplequests.accept.requirements"),
        DAILYFULL("simplequests.accept.daily"),
        DELAY("simplequests.accept.delay"),
        ONETIME("simplequests.accept.onetime"),
        ACCEPT("simplequests.accept.yes"),
        LOCKED("simplequests.accept.locked");

        final String lang;

        AcceptType(String id) {
            this.lang = id;
        }

        public String langKey() {
            return this.lang;
        }
    }
}
