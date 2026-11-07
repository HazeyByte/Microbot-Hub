package net.runelite.client.plugins.microbot.accountstarter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.accountstarter.logic.AccountSnapshot;
import net.runelite.client.plugins.microbot.accountstarter.logic.Action;
import net.runelite.client.plugins.microbot.accountstarter.logic.ApPace;
import net.runelite.client.plugins.microbot.accountstarter.logic.Choices;
import net.runelite.client.plugins.microbot.accountstarter.logic.Decision;
import net.runelite.client.plugins.microbot.accountstarter.logic.FreeSource;
import net.runelite.client.plugins.microbot.accountstarter.logic.Habits;
import net.runelite.client.plugins.microbot.accountstarter.logic.LoopRules;
import net.runelite.client.plugins.microbot.accountstarter.logic.Need;
import net.runelite.client.plugins.microbot.accountstarter.logic.HarlanClaim;
import net.runelite.client.plugins.microbot.accountstarter.logic.NeedResolver;
import net.runelite.client.plugins.microbot.accountstarter.logic.Needs;
import net.runelite.client.plugins.microbot.accountstarter.logic.PanelModel;
import net.runelite.client.plugins.microbot.accountstarter.logic.Planner;
import net.runelite.client.plugins.microbot.accountstarter.logic.PlannerMemory;
import net.runelite.client.plugins.microbot.accountstarter.logic.Progress;
import net.runelite.client.plugins.microbot.accountstarter.logic.Resolution;
import net.runelite.client.plugins.microbot.accountstarter.logic.ResumeState;
import net.runelite.client.plugins.microbot.accountstarter.logic.Spots;
import net.runelite.client.plugins.microbot.accountstarter.logic.Surplus;
import net.runelite.client.plugins.microbot.accountstarter.logic.XpGuard;
import net.runelite.client.plugins.microbot.accountstarter.model.ApPath;
import net.runelite.client.plugins.microbot.accountstarter.model.ApTask;
import net.runelite.client.plugins.microbot.accountstarter.model.TaskState;
import net.runelite.client.plugins.microbot.accountstarter.run.Activity;
import net.runelite.client.plugins.microbot.accountstarter.run.CombatActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.FocusActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.FoodRunActivity;
import net.runelite.client.plugins.microbot.accountstarter.logic.QuestLampChoice;
import net.runelite.client.plugins.microbot.accountstarter.run.MudfootActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.GatherActivity;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.accountstarter.run.TabHome;
import net.runelite.client.plugins.microbot.accountstarter.run.JonActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.LampActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.Outcome;
import net.runelite.client.plugins.microbot.accountstarter.run.SnapshotReader;
import net.runelite.client.plugins.microbot.accountstarter.run.SessionSetup;
import net.runelite.client.plugins.microbot.accountstarter.run.VoucherActivity;
import net.runelite.client.plugins.microbot.accountstarter.run.Watchdogs;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

@Singleton
@Slf4j
public class AccountStarterScript extends Script {
    private static final long PARK_RETRY_MS = 20 * 60_000L;
    private static final int PARK_RETRY_SPREAD_MS = 20 * 60_000;
    private static final int QUEST_TRIES = 3;

    private static final int MAX_LOOP_ERRORS = 20;
    private static final long ERROR_LOG_GAP_MS = 60_000L;
    private static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("microbot").resolve("irked-adventurepaths");

    private AccountStarterConfig config;
    private AccountStarterPlugin plugin;
    private ItemManager itemManager;
    private final Random random = new Random();

    private long accountHash;
    private String accountKey;
    private ResumeStore store;
    private ResumeState state;
    private final TabHome tabHome = new TabHome();
    private Habits habits;
    private ApPace pace;
    private long spotSeed;
    private SnapshotReader reader;
    private PlannerMemory memory;
    private SessionSetup setup = new SessionSetup(new Random());
    private final Map<ApTask, String> parked = Collections.synchronizedMap(new EnumMap<>(ApTask.class));
    private final Set<String> warnedUnbuilt = new HashSet<>();
    private volatile Activity current;
    private volatile Watchdogs watchdogs;
    @Getter
    private volatile Decision currentDecision;
    @Getter
    private volatile AccountSnapshot lastSnapshot;
    private volatile boolean wantsFood;
    private volatile boolean questGaveUp;
    private int questParks;
    private volatile boolean foodBlocked;
    private final Map<ApTask, Long> parkedUntil = new ConcurrentHashMap<>();
    private final AtomicReference<String> sessionSpot = new AtomicReference<>();
    @Getter
    private volatile long sessionStartMs;
    private int firstTick;
    private Decision.Kind lastStepKind;
    private ApTask lastFocused;
    private String lastStepFingerprint;
    @Getter
    private volatile String status = "";
    /** stopPlugin toggles on the EDT later, so the loop must not plan once this is set. */
    private volatile boolean stopping;
    private int loopErrors;
    private long lastErrorLogMs;
    @Getter
    private volatile boolean settled;
    @Getter
    private volatile boolean finished;
    @Getter
    private volatile PanelModel.XpSample xpSample;

    public boolean run(AccountStarterConfig config, AccountStarterPlugin plugin, ItemManager itemManager) {
        this.config = config;
        this.plugin = plugin;
        this.itemManager = itemManager;
        accountHash = -1;
        accountKey = null;
        store = null;
        state = null;
        reader = null;
        parked.clear();
        parkedUntil.clear();
        warnedUnbuilt.clear();
        current = null;
        currentDecision = null;
        lastSnapshot = null;
        sessionStartMs = System.currentTimeMillis();
        firstTick = -1;
        setup = new SessionSetup(new Random());
        lastStepKind = null;
        lastStepFingerprint = null;
        lastFocused = null;
        status = "starting";
        stopping = false;
        loopErrors = 0;
        lastErrorLogMs = 0;
        settled = false;
        finished = false;
        questGaveUp = false;
        questParks = 0;
        foodBlocked = false;
        wantsFood = false;
        sessionSpot.set(null);
        xpSample = null;
        watchdogs = new Watchdogs(config, this::abortActivity, this::stop);
        log.info("Account Starter {} started", AccountStarterPlugin.version);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(this::loop, 0, 300, TimeUnit.MILLISECONDS);
        return true;
    }

    public Map<ApTask, String> getParked() {
        synchronized (parked) {
            // EnumMap's copy constructor throws on an empty non-EnumMap source (parked is a synchronized wrapper)
            Map<ApTask, String> copy = new EnumMap<>(ApTask.class);
            copy.putAll(parked);
            return copy;
        }
    }

    private void loop() {
        try {
            step();
            loopErrors = 0;
        } catch (Exception e) {
            if (!isRunning() || stopping) return;
            loopErrors++;
            long now = System.currentTimeMillis();
            if (loopErrors == 1 || now - lastErrorLogMs >= ERROR_LOG_GAP_MS) {
                lastErrorLogMs = now;
                log.warn("Account Starter: loop error ({} in a row): {}", loopErrors, e.toString());
            }
            if (loopErrors >= MAX_LOOP_ERRORS) stop(MAX_LOOP_ERRORS + " loop errors in a row, last: " + e);
        }
    }

    private void step() {
        if (!super.run() || stopping) return;
        if (!Microbot.isLoggedIn()) {
            settled = false;
            firstTick = -1;
            watchdogs.disarm();
            if (!setup.whileLoggedOut()) stop("could not log in after " + SessionSetup.MAX_LOGIN_TRIES + " tries");
            return;
        }
        if (BreakHandlerScript.isBreakActive() || !ensureAccount()) return;
        if (setup.busy()) return;
        if (setup.backFromLogout()) {
            log.info("Account Starter: back in game; re-planning from the game's state");
            abortCurrent();
        }
        int now = Microbot.getClientThread().invoke(() -> {
            return Microbot.getClient().getTickCount();
        });
        if (firstTick < 0) firstTick = now;
        AccountSnapshot s = read();
        if (!s.isLoggedIn() || !LoopRules.settled(now, firstTick, s)) {
            settled = false;
            return;
        }
        if (!settled) xpSample = null;
        settled = true;
        sampleXp(s);
        Set<Skill> protectedNow = choices().getProtectedSkills();
        if (!watchdogs.armedFor(protectedNow)) {
            watchdogs.arm(s, protectedNow);
            // one hit back gives Hitpoints XP that cannot be taken away
            if (protectedNow.contains(Skill.HITPOINTS) && !Rs2Combat.setAutoRetaliate(false)) {
                stop("could not turn auto retaliate off");
                return;
            }
        }
        Optional<Outcome> w = watchdogs.check(s, activityKey());
        if (w.isPresent()) {
            onWatchdog(w.get());
            return;
        }
        if (current == null || !current.walking()) setup.hold();
        if (current == null && !plan(s)) return;
        Activity a = current;
        if (a == null) return;
        if (a instanceof FoodRunActivity) status = a.describe();
        settle(a.tick(s), s);
        tabHome.tick((a instanceof CombatActivity || a instanceof GatherActivity) && !Rs2Bank.isOpen() && !Rs2Shop.isOpen()
                && !Rs2Dialogue.isInDialogue(), random);
        Global.sleep(random.nextInt(300));
    }

    private boolean ensureAccount() {
        long hash = Microbot.getClientThread().invoke(() -> {
            return Microbot.getClient().getAccountHash();
        });
        if (hash == -1) return false;
        if (hash == accountHash) return true;
        abortCurrent();
        settled = false;
        firstTick = -1;
        lastStepKind = null;
        lastStepFingerprint = null;
        lastFocused = null;
        saveState();
        AccountPersonality p = AccountPersonality.load(DIR, hash);
        accountHash = hash;
        accountKey = p.key();
        store = new ResumeStore(DIR);
        state = store.load(accountKey);
        habits = Habits.draw(p.seedFor("habits"));
        pace = ApPace.draw(p.seedFor("pace")).forSession(random);
        spotSeed = p.seedFor("spots");
        watchdogs.reset(habits.getStuckAfterMs());
        reader = new SnapshotReader(itemManager, state);
        memory = new PlannerMemory();
        Arrays.stream(ApTask.values()).filter(x -> x.name().equals(state.getCurrentTask())).findFirst()
                .ifPresent(memory::setCurrentTask);
        HarlanClaim.load(state.getHarlanClaimMs(), ms -> {
            state.setHarlanClaimMs(ms);
            saveState();
        });
        parked.clear();
        parkedUntil.clear();
        questGaveUp = false;
        questParks = 0;
        foodBlocked = false;
        warnedUnbuilt.clear();
        lastSnapshot = null;
        log.info("Account Starter: account {} loaded{}", p.label(), p.persistent() ? "" : " (session-only personality)");
        return true;
    }

    private void sampleXp(AccountSnapshot s) {
        Decision d = currentDecision;
        Skill skill = d == null || d.getTask() == null ? null : d.getTask().skill();
        if (skill == null) return;
        PanelModel.XpSample old = xpSample;
        if (old != null && old.getSkill() == skill) return;
        xpSample = new PanelModel.XpSample(skill, s.getXp().getOrDefault(skill, 0), System.currentTimeMillis());
    }

    public boolean isWalking() {
        Activity a = current;
        return a != null && a.walking();
    }

    public String walkingTo() {
        Activity a = current;
        return a == null ? null : a.walkingTo();
    }

    public boolean isStopping() {
        return stopping;
    }

    void onGameTick(GameTick e) {
        Watchdogs w = watchdogs;
        if (w != null && isRunning()) w.onGameTick(e);
    }

    private void onWatchdog(Outcome o) {
        if (o.getKind() == Outcome.Kind.CONTINUE) return;
        if (o.getKind() == Outcome.Kind.NATURAL_POINT) clearCurrent();
        settle(o, read());
    }

    private String activityKey() {
        Decision d = currentDecision;
        if (current == null || d == null) return null;
        return d.getTask() != null ? d.getTask().name() : d.getKind().name();
    }

    private boolean plan(AccountSnapshot s) {
        if (stopping) return false;
        retryParked();
        Choices base = choices();
        Choices c = LoopRules.withoutParked(base, getParked().keySet());
        boolean skiller = base.getChosen().stream().noneMatch(t -> t.path() == ApPath.COMBAT);
        Decision d = config.questOnly()
                ? Planner.questOnly(s, config.questLampSkill() != QuestLampChoice.AUTO)
                : Planner.next(s, c, config.pacing(), memory, habits.tuning(), System.currentTimeMillis(), random,
                        false, budget(s), this::bidPrice,
                        t -> LoopRules.tilesTo(s.getLocation(),
                                Spots.usable(t, s.getLevels(), skiller, Needs.edible(s))));
        switch (d.getKind()) {
            case WAIT:
                return current != null;
            case DONE:
                logOutIfWanted();
                if (!parked.isEmpty()) {
                    stop("finished what could be done; parked: " + parkedText());
                } else {
                    finished = true;
                    stop(config.questOnly() ? d.getReason() : "all chosen Adventure Path tasks done and claimed");
                }
                return false;
            case STOP:
                if (varbitStopFaded()) return false;
                stop(parked.isEmpty() ? d.getReason() : d.getReason() + "; parked: " + parkedText());
                return false;
            default:
                break;
        }
        if (current != null) {
            if (sameAs(d)) return true;
            abortCurrent();
        }
        Activity a = activityFor(d, s, c);
        if (a == null) {
            String kind = d.getTask() != null ? (d.getTask().path() == ApPath.COMBAT ? "combat" : "gathering") : d.getKind().name();
            if (warnedUnbuilt.add(kind)) log.info("Account Starter: not built yet: {}", kind);
            if (d.getTask() == null) {
                stop("not built yet: " + kind);
            } else {
                parked.put(d.getTask(), "not built yet: " + kind);
            }
            return false;
        }
        if (stopping) {
            a.abort();
            return false;
        }
        current = a;
        currentDecision = d;
        status = a.describe();
        log.info("Account Starter: {} ({})", a.describe(), d.getReason());
        String task = d.getTask() != null ? d.getTask().name() : d.getKind().name();
        if (!task.equals(state.getCurrentTask())) {
            state.setCurrentTask(task);
            saveState();
        }
        return true;
    }

    Activity activityFor(Decision d, AccountSnapshot s, Choices c) {
        switch (d.getKind()) {
            case STARTER_KIT:
            case CLAIM_AT_JON:
                List<ApTask> unclaimed = s.getProgress().getTasks().entrySet().stream()
                        .filter(e -> e.getValue() == TaskState.DONE_UNCLAIMED).map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                return new JonActivity(d.getKind(), unclaimed, store, accountKey, state, pace, random, config, burying(c),
                        usedLater(s, c));
            case DO_TASK: {
                if (d.getTask() != lastFocused && !Rs2Player.isInCombat() && !Rs2Player.isMoving()) {
                    lastFocused = d.getTask();
                    return new FocusActivity(d.getTask(), habits.getCheckPathsChance(), pace, random);
                }
                Activity food = foodFirst(d.getTask(), s, c);
                if (food != null) return food;
                return d.getTask().path() == ApPath.GATHERER
                        ? new GatherActivity(d.getTask(), c, this::read, pace, random, habits, spotSeed)
                        : new CombatActivity(d.getTask(), c, this::read, pace, random, habits, spotSeed, sessionSpot, config.pickUpArrows());
            }
            case DO_QUEST: {
                if (!config.questOnly() && state.getMudfootStep() == MudfootActivity.Step.START.ordinal()
                        && Needs.edible(s) < MudfootActivity.MIN_FOOD && Needs.bankedFood(s) == 0
                        && NeedResolver.canCatchAndCook(s)) {
                    return new FoodRunActivity(ApTask.RANGED_5, c, this::read, pace, random, spotSeed, MudfootActivity.MIN_FOOD);
                }
                return new MudfootActivity(c, pace, random, config.questOnly(), state.getMudfootStep(), step -> {
                    state.setMudfootStep(step);
                    saveState();
                });
            }
            case USE_LAMP:
                if (s.carried(ApTask.LAMP_ID) > 0 && Planner.lampSkill(c, s.getLevels()).isPresent()) {
                    return new LampActivity(c, ApTask.LAMP_ID, "Lamp of the Gatherer", lv -> config.lampSkill().resolve(c, lv),
                            pace, random);
                }
                return new LampActivity(c, ApTask.QUEST_LAMP_ID, "antique lamp", lv -> config.questLampSkill().resolve(c, lv),
                        pace, random);
            case REDEEM_VOUCHER:
                return new VoucherActivity(d.getVoucher(), pace, random, burying(c));
            default:
                return null;
        }
    }

    private static Set<Integer> usedLater(AccountSnapshot s, Choices c) {
        Set<Integer> out = new HashSet<>();
        for (ApTask t : c.getChosen()) {
            if (s.getProgress().of(t) != TaskState.NOT_DONE) continue;
            Needs.forTask(t, s).forEach(n -> out.addAll(n.getAnyOf()));
            out.addAll(LoopRules.gearPicks(t, s, c));
        }
        out.removeAll(Needs.FOOD);
        return out;
    }

    private Activity foodFirst(ApTask task, AccountSnapshot s, Choices c) {
        if (task.path() != ApPath.COMBAT) return null;
        for (Need n : Needs.forTask(task, s)) {
            if (n.getFree() != FreeSource.CATCH_AND_COOK) continue;
            Resolution r = NeedResolver.resolve(n, s, false, 0, id -> 0, 1);
            if (r.getKind() == Resolution.Kind.FREE) return new FoodRunActivity(task, c, this::read, pace, random, spotSeed);
        }
        return null;
    }

    private static boolean burying(Choices c) {
        return c.isBury() && XpGuard.allowed(Action.BURY, c.getProtectedSkills());
    }

    private int budget(AccountSnapshot s) {
        return Surplus.budget(config.coinBudget(), state.getCoinsSpent(), s.getCoins(), config.coinReserve());
    }

    private void settle(Outcome o, AccountSnapshot s) {
        switch (o.getKind()) {
            case CONTINUE:
                return;
            case NATURAL_POINT:
                if (CombatActivity.OUT_OF_FOOD.equals(o.getReason())) wantsFood = true;
                if (o.isReplan()) clearCurrent();
                plan(s);
                return;
            case DONE: {
                log.info("Account Starter: done: {}", o.getReason());
                Decision d = currentDecision;
                clearCurrent();
                if (d == null || d.getTask() != null) {
                    lastStepKind = null;
                    return;
                }
                String fp = LoopRules.fingerprint(read());
                if (LoopRules.repeatsWithoutProgress(lastStepKind, lastStepFingerprint, d.getKind(), fp)) {
                    stop(d.getKind() == Decision.Kind.CLAIM_AT_JON
                            ? "Jon gave nothing twice; the rewards left may need a members world"
                            : d.getKind() + " finished twice without changing anything");
                    return;
                }
                lastStepKind = d.getKind();
                lastStepFingerprint = fp;
                return;
            }
            default:
                if (JonActivity.OPTED_OUT.equals(o.getReason())) {
                    stop(o.getReason());
                    return;
                }
                if (current instanceof FoodRunActivity) {
                    log.info("Account Starter: no more food runs this session: {}", o.getReason());
                    foodBlocked = true;
                    wantsFood = false;
                    clearCurrent();
                    return;
                }
                Decision d = currentDecision;
                clearCurrent();
                if (d != null && d.getTask() != null) {
                    log.info("Account Starter: parked {}: {}", d.getTask(), o.getReason());
                    parked.put(d.getTask(), o.getReason());
                    parkedUntil.put(d.getTask(), System.currentTimeMillis() + PARK_RETRY_MS + random.nextInt(PARK_RETRY_SPREAD_MS));
                } else if (d != null && d.getKind() == Decision.Kind.DO_QUEST) {
                    if (++questParks < QUEST_TRIES) {
                        log.info("Account Starter: Mudfoot's Big Adventure parked, trying again: {}", o.getReason());
                    } else {
                        log.info("Account Starter: Mudfoot's Big Adventure set aside this session: {}", o.getReason());
                        questGaveUp = true;
                        if (config.questOnly()) stop(o.getReason());
                    }
                } else {
                    stop(o.getReason());
                }
        }
    }

    private AccountSnapshot read() {
        AccountSnapshot base = reader.read(Rs2Bank.getBankLiveEpoch() > 0);
        AccountSnapshot s = base.toBuilder()
                .foodBlocked(foodBlocked).shopsAllowed(config.buyAtVarrockShops()).foodStock(habits.getFoodStock()).protectedSkills(choices().getProtectedSkills()).wantsFood(wantsFood)
                .mudfootToDo(base.isMudfootToDo() && (config.doMudfoot() || config.questOnly()) && !questGaveUp).build();
        if (wantsFood && Needs.edible(s) > 0) wantsFood = false;
        lastSnapshot = s;
        return s;
    }

    /** Not-participating and ironman come from varbits that can lag a login: confirm once before stopping. */
    private boolean varbitStopFaded() {
        Progress p = read().getProgress();
        if (p.isParticipating() && !p.isIronman()) return false;
        Global.sleep(1200);
        p = read().getProgress();
        return p.isParticipating() && !p.isIronman();
    }

    Choices choices() {
        if (config.questOnly()) return Choices.questOnly();
        return Choices.from(config.preset(),
                LoopRules.targets(config.attackTarget(), config.strengthTarget(), config.defenceTarget(),
                        config.rangedTarget(), config.magicTarget(), config.fishingTarget(),
                        config.woodcuttingTarget(), config.miningTarget()),
                config.buryBones(), config.cookFood(), config.cookFood());
    }

    private long guidePrice(long itemId) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> itemManager.getItemPrice((int) itemId)).orElse(0L);
    }

    private long bidPrice(long itemId) {
        return Surplus.buyPrice(guidePrice(itemId), habits.getGePremium(), 0);
    }

    private boolean sameAs(Decision d) {
        Decision c = currentDecision;
        return c != null && c.getKind() == d.getKind() && c.getTask() == d.getTask() && c.getVoucher() == d.getVoucher();
    }

    private String parkedText() {
        return getParked().entrySet().stream().map(e -> e.getKey().name() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private void clearCurrent() {
        current = null;
        currentDecision = null;
    }

    private void abortActivity() {
        Activity a = current;
        if (a != null) a.abort();
    }

    private void abortCurrent() {
        Activity a = current;
        clearCurrent();
        if (a != null) a.abort();
    }

    /** stop() on the script thread and shutdown() on the EDT can both save. */
    private synchronized void saveState() {
        if (store != null && state != null) store.save(accountKey, state);
    }

    /** Runs before stop(): stopPlugin shuts this thread down, and the logout has to finish first. */
    private void logOutIfWanted() {
        if (config.logOutWhenDone()) log.info("Account Starter: logging out: {}", Rs2Player.logoutWithoutAutoLogin());
    }

    private void retryParked() {
        long now = System.currentTimeMillis();
        parkedUntil.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            log.info("Account Starter: trying parked {} again", e.getKey());
            parked.remove(e.getKey());
            return true;
        });
    }

    private void stop(String reason) {
        if (stopping) return;
        stopping = true;
        status = reason;
        log.info("Account Starter: stopping: {}", reason);
        abortCurrent();
        saveState();
        Microbot.stopPlugin(plugin);
    }

    @Override
    public void shutdown() {
        stopping = true;
        super.shutdown();
        abortCurrent();
        saveState();
    }

}
