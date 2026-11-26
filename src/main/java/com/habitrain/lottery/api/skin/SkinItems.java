package com.habitrain.lottery.api.skin;

import com.habitrain.lottery.skin.SkinComponents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Item bindings use registry ids or data-pack tags, never upstream Java item classes. */
public final class SkinItems {
    private static final List<String> TYPES = SkinDefinition.SUPPORTED_TYPES.stream().sorted().toList();
    /**
     * The {@code habitrain_lottery:skin_items/<type>} tags, created once. They were
     * rebuilt per inventory slot per tick by the server-side skin mirror, i.e. 5
     * {@link TagKey#create} calls per slot per player per tick.
     */
    private static final Map<String, TagKey<Item>> TYPE_TAGS = buildTypeTags();
    /** Lock-free lookup map for hot-path {@link #typeOf} and {@link #boundType} queries. */
    private static final ConcurrentHashMap<ResourceLocation, String> FAST_BINDINGS = new ConcurrentHashMap<>();
    /** Authoritative registration-order bindings, guarded by {@link HabiSkinApi#REGISTRY_LOCK}. */
    private static final Map<ResourceLocation, String> ORDERED_BINDINGS = new LinkedHashMap<>();
    /** Authoritative per-type lists in registration order, guarded by {@link HabiSkinApi#REGISTRY_LOCK}. */
    private static final Map<String, List<ResourceLocation>> MUTABLE_BY_TYPE = new LinkedHashMap<>();
    /** Lazily materialized immutable snapshots invalidated on mutation (zero per-bind full-map copy at startup). */
    private static volatile Map<ResourceLocation, String> BINDINGS_SNAPSHOT = Map.of();
    private static volatile Map<String, List<ResourceLocation>> BY_TYPE_SNAPSHOT = Map.of();
    private static final Map<ResourceLocation, String> BINDING_PROVIDERS = new LinkedHashMap<>();
    /**
     * Vanilla stand-ins used when the data-pack tag lists no installed item, so a preview
     * never degrades to a blank paper icon. The tag stays authoritative whenever it resolves.
     */
    private static final Map<String, List<String>> FALLBACK_ITEMS = Map.of(
            "knife", List.of("minecraft:iron_sword"),
            "revolver", List.of("minecraft:crossbow"),
            "bat", List.of("minecraft:stick"),
            "grenade", List.of("minecraft:snowball"),
            "hat", List.of("minecraft:leather_helmet"),
            "back", List.of("minecraft:elytra"));
    private SkinItems() {}

    private static Map<String, TagKey<Item>> buildTypeTags() {
        Map<String, TagKey<Item>> tags = new LinkedHashMap<>();
        for (String type : TYPES) {
            tags.put(type, TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_items/" + type)));
        }
        return Map.copyOf(tags);
    }

    private static void invalidateSnapshots() {
        if (ORDERED_BINDINGS.isEmpty()) {
            BINDINGS_SNAPSHOT = Map.of();
            BY_TYPE_SNAPSHOT = Map.of();
        } else {
            BINDINGS_SNAPSHOT = null;
            BY_TYPE_SNAPSHOT = null;
        }
    }

    private static void addBindingLocked(ResourceLocation item, String canonical) {
        ORDERED_BINDINGS.put(item, canonical);
        FAST_BINDINGS.put(item, canonical);
        MUTABLE_BY_TYPE.computeIfAbsent(canonical, k -> new ArrayList<>()).add(item);
        invalidateSnapshots();
    }

    private static String removeBindingLocked(ResourceLocation item) {
        String prevType = ORDERED_BINDINGS.remove(item);
        if (prevType != null) {
            FAST_BINDINGS.remove(item);
            List<ResourceLocation> list = MUTABLE_BY_TYPE.get(prevType);
            if (list != null) {
                list.remove(item);
                if (list.isEmpty()) {
                    MUTABLE_BY_TYPE.remove(prevType);
                }
            }
            invalidateSnapshots();
        }
        return prevType;
    }

    private static Map<ResourceLocation, String> bindingsSnapshot() {
        Map<ResourceLocation, String> snapshot = BINDINGS_SNAPSHOT;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            snapshot = BINDINGS_SNAPSHOT;
            if (snapshot == null) {
                snapshot = ORDERED_BINDINGS.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(ORDERED_BINDINGS));
                BINDINGS_SNAPSHOT = snapshot;
            }
            return snapshot;
        }
    }

    private static Map<String, List<ResourceLocation>> byTypeSnapshot() {
        Map<String, List<ResourceLocation>> snapshot = BY_TYPE_SNAPSHOT;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            snapshot = BY_TYPE_SNAPSHOT;
            if (snapshot == null) {
                if (MUTABLE_BY_TYPE.isEmpty()) {
                    snapshot = Map.of();
                } else {
                    Map<String, List<ResourceLocation>> frozen = new LinkedHashMap<>(MUTABLE_BY_TYPE.size());
                    for (Map.Entry<String, List<ResourceLocation>> entry : MUTABLE_BY_TYPE.entrySet()) {
                        frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
                    }
                    snapshot = Collections.unmodifiableMap(frozen);
                }
                BY_TYPE_SNAPSHOT = snapshot;
            }
            return snapshot;
        }
    }

    public static void bindItem(ResourceLocation item, String type) {
        String canonical = SkinDefinition.normalizeType(type);
        Objects.requireNonNull(item, "item");
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            String provider = HabiSkinApi.currentProvider();
            String previous = ORDERED_BINDINGS.get(item);
            if (previous != null && !previous.equals(canonical)) {
                throw new IllegalStateException("Conflicting skin item: " + item);
            }
            if (previous == null) {
                addBindingLocked(item, canonical);
                String prevProvider = provider == null
                        ? BINDING_PROVIDERS.remove(item)
                        : BINDING_PROVIDERS.put(item, provider);
                SkinRegistrationTransaction.recordUndo(() -> {
                    synchronized (HabiSkinApi.REGISTRY_LOCK) {
                        if (canonical.equals(ORDERED_BINDINGS.get(item))) {
                            removeBindingLocked(item);
                        }
                        if (prevProvider == null) {
                            BINDING_PROVIDERS.remove(item);
                        } else {
                            BINDING_PROVIDERS.put(item, prevProvider);
                        }
                    }
                });
            } else if (provider != null && !BINDING_PROVIDERS.containsKey(item)) {
                BINDING_PROVIDERS.put(item, provider);
                SkinRegistrationTransaction.recordUndo(() -> {
                    synchronized (HabiSkinApi.REGISTRY_LOCK) {
                        BINDING_PROVIDERS.remove(item);
                    }
                });
            }
        }
    }

    static void removeProvider(String providerId) {
        if (providerId == null) {
            return;
        }
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            List<ResourceLocation> owned = new ArrayList<>();
            for (Map.Entry<ResourceLocation, String> entry : BINDING_PROVIDERS.entrySet()) {
                if (providerId.equals(entry.getValue())) {
                    owned.add(entry.getKey());
                }
            }
            if (owned.isEmpty()) {
                return;
            }
            for (ResourceLocation item : owned) {
                String prevType = removeBindingLocked(item);
                String prevProvider = BINDING_PROVIDERS.remove(item);
                if (prevType != null) {
                    SkinRegistrationTransaction.recordUndo(() -> {
                        synchronized (HabiSkinApi.REGISTRY_LOCK) {
                            if (!ORDERED_BINDINGS.containsKey(item)) {
                                addBindingLocked(item, prevType);
                            }
                            if (prevProvider != null) {
                                BINDING_PROVIDERS.put(item, prevProvider);
                            }
                        }
                    });
                }
            }
        }
    }

    static void clearForTest() {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            ORDERED_BINDINGS.clear();
            FAST_BINDINGS.clear();
            MUTABLE_BY_TYPE.clear();
            BINDING_PROVIDERS.clear();
            invalidateSnapshots();
        }
    }

    /** Immutable snapshot of explicitly registered item-to-skin-type bindings in registration order. */
    public static Map<ResourceLocation, String> bindings() {
        return bindingsSnapshot();
    }

    static String boundType(ResourceLocation item) {
        return item == null ? null : FAST_BINDINGS.get(item);
    }

    public static String typeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String bound = FAST_BINDINGS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (bound != null) return bound;
        for (String type : TYPES) {
            TagKey<Item> tag = TYPE_TAGS.get(type);
            if (tag != null && stack.is(tag)) return type;
        }
        return null;
    }

    /**
     * The raw {@code type/id} skin component carried by {@code stack}, or {@code null}
     * when the stack carries none.
     *
     * <p>This is the same value {@code SkinDefinition} registrations and mail
     * attachments are keyed by, so an integration that has a stack in hand (a flying
     * projectile, an item entity, a custom renderer) can resolve its skin without
     * reaching into this mod's internals.</p>
     */
    public static String entryOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.get(SkinComponents.SKIN);
    }

    public static ItemStack preview(String entry) {
        var definition = HabiSkinApi.fromEntry(entry);
        if (definition.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(SkinComponents.SKIN, definition.get().type() + "/" + definition.get().id());
        return stack;
    }

    /**
     * The item a skin type is carried by, in priority order: an explicit
     * {@link #bindItem} registration, the first installed entry of the
     * {@code habitrain_lottery:skin_items/<type>} tag, then a vanilla stand-in. Never
     * air, so the wardrobe can render a real "default appearance" icon and a real
     * third-person base item instead of an unrelated placeholder.
     *
     * <p>{@code bindItem} used to affect recognition only, so a mod that bound its own
     * item with {@code SkinItems.bindItem(...)} still previewed and dressed the vanilla
     * stand-in (audit F-09). API bindings now win over the tag here too, matching the
     * documented "API 绑定优先于标签" rule; the tag remains the fallback for skins
     * supplied purely through a data pack.</p>
     */
    public static Item defaultItem(String type) {
        String canonical = SkinDefinition.normalizeType(type);
        Item bound = firstBoundItem(canonical);
        if (bound != null) return bound;
        TagKey<Item> tag = TYPE_TAGS.get(canonical);
        if (tag != null) {
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                Item item = holder.value();
                if (item != null && item != Items.AIR) return item;
            }
        }
        for (String id : FALLBACK_ITEMS.getOrDefault(canonical, List.of())) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR) return item;
        }
        return Items.PAPER;
    }

    /**
     * The first still-installed item explicitly bound to {@code canonicalType}, or
     * {@code null} when the type has no usable binding. Bindings are kept in
     * registration order so the first extension to bind an item owns the preview base.
     */
    private static Item firstBoundItem(String canonicalType) {
        List<ResourceLocation> items = byTypeSnapshot().get(canonicalType);
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (ResourceLocation itemId : items) {
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item != null && item != Items.AIR) return item;
        }
        return null;
    }

    /** The type's unskinned item, i.e. what "default appearance" resolves to. */
    public static ItemStack defaultStack(String type) {
        return new ItemStack(defaultItem(type));
    }

    /**
     * The type's real item carrying the requested skin component, i.e. what other players
     * would receive. Returns an empty stack for an unregistered {@code type/id} pair.
     */
    public static ItemStack styled(String type, String id) {
        var definition = HabiSkinApi.find(type, id);
        if (definition.isEmpty()) return ItemStack.EMPTY;
        String canonical = definition.get().type();
        ItemStack stack = new ItemStack(defaultItem(canonical));
        stack.set(SkinComponents.SKIN, canonical + "/" + definition.get().id());
        return stack;
    }
}
