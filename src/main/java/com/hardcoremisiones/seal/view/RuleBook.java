package com.hardcoremisiones.seal.view;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/** Libro consultable desde el Sello, sin ocupar una ranura adicional. */
public final class RuleBook {
    private RuleBook() { }
    public static ItemStack crear() {
        ItemStack libro=new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta=(BookMeta)libro.getItemMeta();
        if(meta==null)return libro;
        meta.setTitle("Las leyes del mundo");
        meta.setAuthor("El mundo");
        meta.setPages(List.of(
            "§6§lLAS LEYES DEL MUNDO§0\n\nEl mundo observa. Cada promesa cumplida prolonga su favor. Cada deuda impaga tendrá consecuencias.\n\n§8Este libro no revela todos los secretos.",
            "§6§lI. CADA JORNADA§0\n\nRecibirás una misión individual cada día. La medianoche dicta su resultado.\n\nEl panel lateral señala el plazo y el Sello explica cómo cumplirla.",
            "§6§lII. EL EQUIPO§0\n\nComparten una misión grupal. En los primeros días dura tres jornadas; desde el día 10, dos.\n\nAlgunas ofrendas especiales exigen que cada integrante contribuya.",
            "§6§lIII. OFRENDAS§0\n\nUsá el Sello del Juicio con clic derecho.\n\nLas entregas son definitivas; podés aportar por partes. Fabricar, explorar, minar o combatir también puede contar.",
            "§6§lIV. DORMIR§0\n\nNo se puede descansar mientras alguna misión individual permanezca pendiente.\n\nDesconectarse no permite saltarse una misión. La grupal bloquea la última noche si sigue pendiente.",
            "§6§lV. EL JUICIO§0\n\nLas deudas individuales generan penitencias propias, incluso si el infractor está desconectado. Los enemigos del juicio brillan de violeta.\n\nDerrotá a todos los del juicio. Sobrevivir salda la deuda, sin recompensa extra.",
            "§6§lVI. RECOMPENSAS§0\n\nCumplir misiones otorga premios modestos, y completar la grupal ayuda a todos.\n\nSeis jornadas perfectas y dos grupales desbloquean una tregua individual al día siguiente.",
            "§6§lVII. NETHER§0\n\nLos deberes del Overworld se suspenden al entrar a otra dimensión. Al volver, el juicio se reprograma para una medianoche válida.",
            "§6§lVIII. EXPEDICIÓN§0\n\nUna fortaleza, bastión o escombros desbloquean una ofrenda grupal. El plazo solo corre cuando están juntos en Nether. Cumplirla otorga un premio; si no hay espacio, queda pendiente.",
            "§6§lIX. LA PARTIDA§0\n\nUna muerte reinicia el mundo de todos. Las ofrendas y rachas pertenecen a la partida; las muertes históricas permanecen en el servidor.",
            "§5§lX. MALDICIONES§0\n\nSi el administrador activa W37, una falta puede causar un efecto breve al día siguiente: Debilidad, Lentitud, Fatiga Minera u Oscuridad. No se acumulan; esperan un lugar seguro y el fin de los Juicios."
        ));
        libro.setItemMeta(meta);
        return libro;
    }
}
