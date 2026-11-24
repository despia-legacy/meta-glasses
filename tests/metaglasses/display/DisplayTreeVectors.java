package despia.ext.metaglasses;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Runs display-vectors.json through GlassesCore.DisplayTree; the Swift twin prints the same lines. */
public final class DisplayTreeVectors {
    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Object all = new GlassesCore.DisplayTree.Reader(text).value(0);
        for (Object o : (List<?>) all) {
            Map<?, ?> v = (Map<?, ?>) o;
            String line;
            try {
                line = "ok " + canon(GlassesCore.DisplayTree.parse((String) v.get("doc")));
            } catch (GlassesCore.DisplayTree.Invalid e) {
                line = "bad";
            }
            System.out.println(v.get("name") + "\t" + line);
        }
    }

    static String canon(GlassesCore.DisplayTree.Node n) {
        StringBuilder b = new StringBuilder("(").append(n.kind);
        for (Map.Entry<String, String> e : n.attrs.entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue());
        for (GlassesCore.DisplayTree.Node c : n.children) b.append(' ').append(canon(c));
        return b.append(')').toString();
    }
}
