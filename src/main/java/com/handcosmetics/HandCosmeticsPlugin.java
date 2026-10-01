package com.handcosmetics;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

public final class HandCosmeticsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String,Def> defs=new LinkedHashMap<>();
    private final Map<UUID,EnumMap<HandSlot,String>> equipped=new HashMap<>();
    private File playerFile; private YamlConfiguration playerData; private BukkitTask refreshTask;
    private NamespacedKey actionKey, idKey;
    private final Set<UUID> ourMenus=new HashSet<>();

    enum HandSlot { MAIN_HAND, OFF_HAND;
        EquipmentSlot equipment(){ return this==MAIN_HAND?EquipmentSlot.HAND:EquipmentSlot.OFF_HAND; }
    }
    record Def(String id,String name,String item,String guiItem,HandSlot slot,String permission,List<String> lore){}

    @Override public void onEnable(){
        saveDefaultConfig();
        actionKey=new NamespacedKey(this,"action"); idKey=new NamespacedKey(this,"cosmetic_id");
        playerFile=new File(getDataFolder(),"players.yml");
        playerData=YamlConfiguration.loadConfiguration(playerFile);
        loadDefs(); loadPlayers();
        Bukkit.getPluginManager().registerEvents(this,this);
        PluginCommand c=Objects.requireNonNull(getCommand("handcosmetic"));
        c.setExecutor(this); c.setTabCompleter(this);
        startRefresh();
        getLogger().info("Enabled. CosmeticsCore menu bridge: "+getConfig().getBoolean("cosmeticscore-menu.enabled",true));
    }
    @Override public void onDisable(){ if(refreshTask!=null)refreshTask.cancel(); savePlayers(); restoreAll(); }

    private void loadDefs(){
        reloadConfig(); defs.clear();
        ConfigurationSection s=getConfig().getConfigurationSection("cosmetics");
        if(s==null)return;
        for(String id:s.getKeys(false)){
            String b="cosmetics."+id+".";
            String item=getConfig().getString(b+"item","");
            String gui=getConfig().getString(b+"gui-item",item);
            String name=getConfig().getString(b+"display-name",pretty(id));
            String perm=getConfig().getString(b+"permission","");
            List<String> lore=getConfig().getStringList(b+"lore");
            try{
                HandSlot slot=HandSlot.valueOf(getConfig().getString(b+"slot","MAIN_HAND").toUpperCase(Locale.ROOT));
                if(!item.isBlank()) defs.put(id.toLowerCase(Locale.ROOT),new Def(id,name,item,gui,slot,perm,lore));
            }catch(Exception ex){getLogger().warning("Invalid slot for "+id);}
        }
    }
    private static String pretty(String s){
        StringBuilder b=new StringBuilder();
        for(String p:s.replace('-','_').split("_")) if(!p.isEmpty()) b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return b.toString().trim();
    }
    private void loadPlayers(){
        equipped.clear();
        ConfigurationSection s=playerData.getConfigurationSection("players"); if(s==null)return;
        for(String us:s.getKeys(false)) try{
            UUID u=UUID.fromString(us); EnumMap<HandSlot,String> map=new EnumMap<>(HandSlot.class);
            for(HandSlot slot:HandSlot.values()){
                String id=playerData.getString("players."+us+"."+slot.name());
                if(id!=null&&defs.containsKey(id.toLowerCase(Locale.ROOT)))map.put(slot,id.toLowerCase(Locale.ROOT));
            }
            if(!map.isEmpty())equipped.put(u,map);
        }catch(Exception ignored){}
    }
    private void savePlayers(){
        playerData.set("players",null);
        equipped.forEach((u,m)->m.forEach((slot,id)->playerData.set("players."+u+"."+slot.name(),id)));
        try{playerData.save(playerFile);}catch(IOException e){getLogger().warning("Could not save players.yml: "+e);}
    }
    private void startRefresh(){
        if(refreshTask!=null)refreshTask.cancel();
        long ticks=Math.max(1,getConfig().getLong("refresh-ticks",5));
        refreshTask=Bukkit.getScheduler().runTaskTimer(this,()->{
            for(Player target:Bukkit.getOnlinePlayers()) if(equipped.containsKey(target.getUniqueId())) apply(target);
        },1,ticks);
    }

    private ItemStack ia(String id){
        if(id==null||id.isBlank())return null;
        try{
            Class<?> c=Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object cs=c.getMethod("getInstance",String.class).invoke(null,id);
            if(cs==null)return null;
            Object o=c.getMethod("getItemStack").invoke(cs);
            return o instanceof ItemStack i?i.clone():null;
        }catch(Throwable ignored){return null;}
    }
    private ItemStack icon(String iaId,Material fallback,String name,List<String> lore,String action,String cosmeticId){
        ItemStack i=ia(iaId); if(i==null)i=new ItemStack(fallback);
        ItemMeta m=i.getItemMeta(); m.setDisplayName(color(name));
        if(lore!=null&&!lore.isEmpty())m.setLore(lore.stream().map(this::color).toList());
        if(action!=null)m.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);
        if(cosmeticId!=null)m.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,cosmeticId);
        i.setItemMeta(m); return i;
    }
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}

    public boolean equip(Player p,String id){
        Def d=defs.get(id.toLowerCase(Locale.ROOT));
        if(d==null||(!d.permission.isBlank()&&!p.hasPermission(d.permission)))return false;
        if(ia(d.item)==null)return false;
        equipped.computeIfAbsent(p.getUniqueId(),x->new EnumMap<>(HandSlot.class)).put(d.slot,d.id.toLowerCase(Locale.ROOT));
        savePlayers(); apply(p); return true;
    }
    public void clear(Player p,HandSlot slot){
        EnumMap<HandSlot,String> m=equipped.get(p.getUniqueId());
        if(m!=null){m.remove(slot);if(m.isEmpty())equipped.remove(p.getUniqueId());}
        savePlayers(); restoreSlot(p,slot);
    }
    public String getEquipped(Player p,HandSlot slot){
        EnumMap<HandSlot,String> m=equipped.get(p.getUniqueId());return m==null?null:m.get(slot);
    }
    private void apply(Player target){
        EnumMap<HandSlot,String> m=equipped.get(target.getUniqueId());if(m==null)return;
        for(var e:m.entrySet()){
            Def d=defs.get(e.getValue());if(d==null)continue;ItemStack item=ia(d.item);if(item==null)continue;
            for(Player viewer:Bukkit.getOnlinePlayers()) viewer.sendEquipmentChange(target,e.getKey().equipment(),item);
        }
    }
    private void restoreSlot(Player target,HandSlot slot){
        ItemStack real=slot==HandSlot.MAIN_HAND?target.getInventory().getItemInMainHand():target.getInventory().getItemInOffHand();
        for(Player viewer:Bukkit.getOnlinePlayers())viewer.sendEquipmentChange(target,slot.equipment(),real);
    }
    private void restoreAll(){for(Player p:Bukkit.getOnlinePlayers())for(HandSlot s:HandSlot.values())restoreSlot(p,s);}
    private void soon(Player p){Bukkit.getScheduler().runTaskLater(this,()->{if(p.isOnline())apply(p);},1);}

    // ----- CosmeticsCore /cosmetics inventory injection -----
    @EventHandler(priority=EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p)||!getConfig().getBoolean("cosmeticscore-menu.enabled",true))return;
        String title=e.getView().getTitle();
        List<String> matches=getConfig().getStringList("cosmeticscore-menu.title-contains");
        boolean match=matches.isEmpty()?title.toLowerCase(Locale.ROOT).contains("cosmetic"):
            matches.stream().anyMatch(x->title.toLowerCase(Locale.ROOT).contains(color(x).toLowerCase(Locale.ROOT)));
        if(!match)return;
        // Never inject into our own category menus.
        if(ourMenus.contains(p.getUniqueId()))return;
        Bukkit.getScheduler().runTask(this,()->injectButtons(p,e.getInventory()));
    }
    private void injectButtons(Player p,Inventory inv){
        if(!p.isOnline())return;
        int main=findSlot(inv,getConfig().getIntegerList("cosmeticscore-menu.preferred-slots.main-hand"));
        int off=findSlot(inv,getConfig().getIntegerList("cosmeticscore-menu.preferred-slots.off-hand"),main);
        if(main>=0)inv.setItem(main,icon(getConfig().getString("cosmeticscore-menu.main-hand.icon","flamebourne_handcosmetics:main_hand"),
                Material.BLAZE_ROD,getConfig().getString("cosmeticscore-menu.main-hand.name","&6Main Hand"),
                getConfig().getStringList("cosmeticscore-menu.main-hand.lore"),"OPEN_MAIN",null));
        if(off>=0)inv.setItem(off,icon(getConfig().getString("cosmeticscore-menu.off-hand.icon","flamebourne_handcosmetics:off_hand"),
                Material.SHIELD,getConfig().getString("cosmeticscore-menu.off-hand.name","&bOff Hand"),
                getConfig().getStringList("cosmeticscore-menu.off-hand.lore"),"OPEN_OFF",null));
    }
    private int findSlot(Inventory inv,List<Integer> preferred,int...excluded){
        Set<Integer> ex=new HashSet<>();for(int x:excluded)ex.add(x);
        for(int s:preferred)if(s>=0&&s<inv.getSize()&&!ex.contains(s)&&isBlank(inv.getItem(s)))return s;
        for(int s=inv.getSize()-1;s>=0;s--)if(!ex.contains(s)&&isBlank(inv.getItem(s)))return s;
        return -1;
    }
    private boolean isBlank(ItemStack i){return i==null||i.getType().isAir();}

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        ItemStack i=e.getCurrentItem();if(i==null||!i.hasItemMeta())return;
        ItemMeta meta=i.getItemMeta();
        String action=meta.getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);
        if(action==null)return;
        e.setCancelled(true);
        switch(action){
            case "OPEN_MAIN"->openCategory(p,HandSlot.MAIN_HAND,0);
            case "OPEN_OFF"->openCategory(p,HandSlot.OFF_HAND,0);
            case "BACK"->{ourMenus.remove(p.getUniqueId());p.closeInventory();Bukkit.getScheduler().runTask(this,()->p.performCommand(getConfig().getString("cosmeticscore-menu.back-command","cosmetics")));}
            case "CLEAR_MAIN"->{clear(p,HandSlot.MAIN_HAND);openCategory(p,HandSlot.MAIN_HAND,0);}
            case "CLEAR_OFF"->{clear(p,HandSlot.OFF_HAND);openCategory(p,HandSlot.OFF_HAND,0);}
            case "EQUIP"->{String id=meta.getPersistentDataContainer().get(idKey,PersistentDataType.STRING);if(id!=null)equip(p,id);Def d=defs.get(id);openCategory(p,d==null?HandSlot.MAIN_HAND:d.slot,0);}
        }
    }
    private void openCategory(Player p,HandSlot slot,int page){
        int size=54; String title=color(slot==HandSlot.MAIN_HAND?"&8Cosmetics » Main Hand":"&8Cosmetics » Off Hand");
        Inventory inv=Bukkit.createInventory(null,size,title); ourMenus.add(p.getUniqueId());
        String current=getEquipped(p,slot);int pos=0;
        for(Def d:defs.values()){
            if(d.slot!=slot||(!d.permission.isBlank()&&!p.hasPermission(d.permission)))continue;
            List<String> lore=new ArrayList<>(d.lore);
            lore.add(""); lore.add(d.id.equalsIgnoreCase(current)?"&aCurrently equipped":"&eClick to equip");
            inv.setItem(pos++,icon(d.guiItem,Material.PAPER,"&f"+d.name,lore,"EQUIP",d.id));
            if(pos>=45)break;
        }
        inv.setItem(45,icon("",Material.ARROW,"&eBack",List.of("&7Return to CosmeticsCore"),"BACK",null));
        inv.setItem(49,icon("",Material.BARRIER,"&cUnequip",List.of("&7Show your real "+(slot==HandSlot.MAIN_HAND?"main-hand":"off-hand")+" item"),slot==HandSlot.MAIN_HAND?"CLEAR_MAIN":"CLEAR_OFF",null));
        p.openInventory(inv);
    }
    @EventHandler public void close(InventoryCloseEvent e){if(e.getPlayer() instanceof Player p)Bukkit.getScheduler().runTaskLater(this,()->{if(!p.getOpenInventory().getTitle().contains("Cosmetics »"))ourMenus.remove(p.getUniqueId());},1);}

    @EventHandler public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(this,()->{for(Player p:Bukkit.getOnlinePlayers())apply(p);},10);}
    @EventHandler public void quit(PlayerQuitEvent e){ourMenus.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void tp(PlayerTeleportEvent e){soon(e.getPlayer());}
    @EventHandler public void world(PlayerChangedWorldEvent e){soon(e.getPlayer());}
    @EventHandler public void held(PlayerItemHeldEvent e){soon(e.getPlayer());}
    @EventHandler public void swap(PlayerSwapHandItemsEvent e){soon(e.getPlayer());}
    @EventHandler public void invDrag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p)soon(p);}
    @EventHandler(priority=EventPriority.MONITOR) public void invClickRefresh(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p)soon(p);}
    @EventHandler public void respawn(PlayerRespawnEvent e){Bukkit.getScheduler().runTaskLater(this,()->apply(e.getPlayer()),5);}

    @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a){
        if(a.length==0){if(s instanceof Player p)openCategory(p,HandSlot.MAIN_HAND,0);else s.sendMessage("/hc reload");return true;}
        if(a[0].equalsIgnoreCase("reload")){if(s.hasPermission("handcosmetics.admin")){loadDefs();loadPlayers();startRefresh();s.sendMessage("§aHand cosmetics reloaded.");}return true;}
        if(!(s instanceof Player p))return true;
        switch(a[0].toLowerCase(Locale.ROOT)){
            case "main"->openCategory(p,HandSlot.MAIN_HAND,0);
            case "off"->openCategory(p,HandSlot.OFF_HAND,0);
            case "equip"->{if(a.length>1)p.sendMessage(equip(p,a[1])?"§aEquipped.":"§cCould not equip.");}
            case "clear"->{if(a.length>1&&a[1].equalsIgnoreCase("off"))clear(p,HandSlot.OFF_HAND);else clear(p,HandSlot.MAIN_HAND);}
            case "list"->p.sendMessage(String.join(", ",defs.keySet()));
        }return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] x){
        if(x.length==1)return List.of("main","off","equip","clear","list","reload");
        if(x.length==2&&x[0].equalsIgnoreCase("equip"))return new ArrayList<>(defs.keySet());
        if(x.length==2&&x[0].equalsIgnoreCase("clear"))return List.of("main","off");
        return List.of();
    }
}