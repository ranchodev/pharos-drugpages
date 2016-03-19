package ix.idg.controllers;

import ix.core.models.Publication;
import ix.core.models.VNum;
import ix.idg.models.Target;

import java.text.Normalizer;
import java.util.*;

/**
 * @author Rajarshi Guha
 */
public class WordCloud {

    private static final String[] PUNCTUTATION = new String[]{
            ".", "(", ")", ";", ",", "*", "!", "/", "?", "<", ">", "[", "]", "+", ":", "'", "\""
    };

    private static final String[] STOPWORDS = new String[]{
            "a", "x",
            "about", "above", "across", "after", "again", "against", "all", "almost", "alone", "along", "already",
            "also", "although", "always", "among", "an", "and", "another", "any", "anybody", "anyone", "anything",
            "anywhere", "are", "area", "areas", "around", "as", "ask", "asked", "asking", "asks", "at", "away",
            "back", "backed", "backing", "backs", "be", "became", "because", "become", "becomes", "been", "before",
            "began", "behind", "being", "beings", "best", "better", "between", "big", "both", "but", "by", "c", "came",
            "can", "cannot", "case", "cases", "certain", "certainly", "clear", "clearly", "come", "could", "d", "did",
            "differ", "different", "differently", "do", "does", "done", "down", "down", "downed", "downing", "downs",
            "during", "e", "each", "early", "either", "end", "ended", "ending", "ends", "enough", "even", "evenly",
            "ever", "every", "everybody", "everyone", "everything", "everywhere", "f", "face", "faces", "fact", "facts",
            "far", "felt", "few", "find", "finds", "first", "for", "four", "from", "full", "fully", "further",
            "furthered", "furthering", "furthers", "g", "gave", "general", "generally", "get", "gets", "give",
            "given", "gives", "go", "going", "good", "goods", "got", "great", "greater", "greatest", "group", "grouped",
            "grouping", "groups", "h", "had", "has", "have", "having", "he", "her", "here", "herself", "high", "high",
            "high", "higher", "highest", "him", "himself", "his", "how", "however", "i", "if", "important", "in",
            "interest", "interested", "interesting", "interests", "into", "is", "it", "its", "itself", "j", "just",
            "k", "keep", "keeps", "kind", "knew", "know", "known", "knows", "l", "large", "largely", "last", "later",
            "latest", "least", "less", "let", "lets", "like", "likely", "long", "longer", "longest", "m", "made",
            "make", "making", "man", "many", "may", "me", "member", "members", "men", "might", "more", "most", "mostly",
            "mr", "mrs", "much", "must", "my", "myself", "n", "necessary", "need", "needed", "needing", "needs", "never",
            "new", "new", "newer", "newest", "next", "no", "nobody", "non", "noone", "not", "nothing", "now", "nowhere",
            "number", "numbers", "o", "of", "off", "often", "old", "older", "oldest", "on", "once", "one", "only", "open",
            "opened", "opening", "opens", "or", "order", "ordered", "ordering", "orders", "other", "others", "our",
            "out", "over", "p", "part", "parted", "parting", "parts", "per", "perhaps", "place", "places", "point",
            "pointed", "pointing", "points", "possible", "present", "presented", "presenting", "presents", "problem",
            "problems", "put", "puts", "q", "quite", "r", "rather", "really", "right", "right", "room", "rooms", "s",
            "said", "same", "saw", "say", "says", "second", "seconds", "see", "seem", "seemed", "seeming", "seems",
            "sees", "several", "shall", "she", "should", "show", "showed", "showing", "shows", "side", "sides",
            "since", "small", "smaller", "smallest", "so", "some", "somebody", "someone", "something", "somewhere",
            "state", "states", "still", "still", "such", "sure", "t", "take", "taken", "than", "that", "the", "their",
            "them", "then", "there", "therefore", "these", "they", "thing", "things", "think", "thinks", "this",
            "those", "though", "thought", "thoughts", "three", "through", "thus", "to", "today", "together", "too",
            "took", "toward", "turn", "turned", "turning", "turns", "two", "u", "under", "until", "up", "upon", "us",
            "use", "used", "uses", "v", "very", "w", "want", "wanted", "wanting", "wants", "was", "way", "ways", "we",
            "well", "wells", "went", "were", "what", "when", "where", "whether", "which", "while", "who", "whole",
            "whose", "why", "will", "with", "within", "without", "work", "worked", "working", "works", "would", "year",
            "years", "yet", "you", "young", "younger", "youngest", "your", "yours"
    };

    static String normalize(String s) {
        s = s.toLowerCase().replaceAll("[0-9]","").replaceAll(" [a-z] ", "");
        for (String p : PUNCTUTATION) s = s.replace(p, " ");
        s = s.trim();
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        return s.replaceAll("[^\\x00-\\x7F]", "");
    }

    public static List<VNum> textHistogram(Target t, String textType, String transform) throws Exception {
        List<String> words = new ArrayList<>();
        List<Publication> pubs = IDGApp.getPublications(t);
        for (Publication p : pubs) {
            if (textType.equalsIgnoreCase("title"))
                Collections.addAll(words, p.title.split(" "));
            else if (textType.equalsIgnoreCase("abstract") && p.abstractText != null)
                Collections.addAll(words, p.abstractText.split(" "));
            else throw new IllegalArgumentException("Must specify title or abstract for textType");
        }

        // remove stop words
        List<String> swl = Arrays.asList(STOPWORDS);
        List<String> cleaned = new ArrayList<>();
        for (String w : words) {
            w = normalize(w);
            if (w.equals("")) continue;
            if (!swl.contains(w))
                cleaned.add(w);
        }

        Map<String, Integer> hist = new HashMap<>();
        for (String w : cleaned) {
            Integer c;
            if (hist.containsKey(w))
                c = hist.get(w) + 1;
            else c = 1;
            hist.put(w, c);
        }

        List<VNum> counts = new ArrayList<>();
        for (String w : hist.keySet()) {
            if (transform == null)
                counts.add(new VNum(w, (double) hist.get(w)));
            else if (transform.equalsIgnoreCase("log"))
                counts.add(new VNum(w, Math.log10((double) hist.get(w))));
            else if (transform.equalsIgnoreCase("sqrt"))
                counts.add(new VNum(w, Math.sqrt((double) hist.get(w))));
            else
                counts.add(new VNum(w, (double) hist.get(w)));
        }

        return (counts);
    }

}
