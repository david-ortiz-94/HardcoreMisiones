package com.hardcoremisiones.ui.sidebar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Immutable sidebar frames and minimal score changes. This layer neither
 * queries a Minecraft player nor writes a scoreboard; the Paper adapter applies
 * changes on its owning server thread. The historical entry suffix and order
 * remain stable even for identical visible lines.
 */
public final class SidebarPresentation {
    private static final String SUFFIXES="0123456789abcdef";
    private SidebarPresentation(){}
    /** @param title visible title including formatting codes
     * @param rows visible rows in top-to-bottom order, at most sixteen
     */
    public record Frame(String title,List<String> rows){
        public Frame {
            Objects.requireNonNull(title);rows=List.copyOf(rows);
            if(rows.size()>SUFFIXES.length())throw new IllegalArgumentException("Demasiadas filas de panel");
        }
    }
    /** @param removals previous entries no longer present
     * @param updates new entries or entries whose score changed
     */
    public record Delta(List<String> removals,Map<String,Integer> updates){
        public Delta{removals=List.copyOf(removals);updates=Map.copyOf(updates);}
    }
    /** Evaluates candidates in descending precedence and stops at the first
     * non-null frame. Lower-priority suppliers are never invoked while a
     * judgement or encounter occupies the panel.
     * @param candidates ordered suppliers; null frame means unavailable
     * @return the first available frame
     * @throws IllegalStateException if even the fallback is unavailable
     */
    public static Frame choose(List<Supplier<Frame>> candidates){
        for(var supplier:candidates){var frame=supplier.get();if(frame!=null)return frame;}
        throw new IllegalStateException("Sin panel de respaldo");
    }
    /** Computes only changed scoreboard entries. A null previous frame denotes
     * first render. Titles are handled independently by the Paper adapter.
     * @param previous last applied frame or null
     * @param next desired frame
     * @return immutable removal and update sets
     */
    public static Delta delta(Frame previous,Frame next){
        var old=previous==null?Map.<String,Integer>of():entries(previous);
        var current=entries(Objects.requireNonNull(next));
        var remove=new ArrayList<String>();var update=new LinkedHashMap<String,Integer>();
        for(String key:old.keySet())if(!current.containsKey(key))remove.add(key);
        current.forEach((key,score)->{if(!Objects.equals(score,old.get(key)))update.put(key,score);});
        return new Delta(remove,update);
    }
    private static Map<String,Integer> entries(Frame frame){
        var entries=new LinkedHashMap<String,Integer>();
        for(int i=0;i<frame.rows().size();i++)entries.put(frame.rows().get(i)+"§"+SUFFIXES.charAt(i),frame.rows().size()-i);
        return entries;
    }
}
