package rw.terimbere.csams.modules.memberpayment.receipt;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

/**
 * Decides what a receipt CLAIMS happened to the payment: succeeded, failed, still pending, or unknown. It is a claim
 * made by an image, never evidence that money moved.
 *
 * <p>The text is normalised (case, accents, punctuation, line breaks), cut off where a list of other transactions
 * begins, and matched against the {@link ReceiptVocabulary} as whole words, longest phrase first, so a phrase such as
 * "not successful" is one failed indicator and "successful" inside it never votes. When indicators of more than one
 * outcome remain for the transaction itself, or none at all, the answer is UNKNOWN and the receipt needs a person.
 */
@Component
public final class ReceiptStatusClassifier {

    /** What was decided and the (normalised) phrases that decided it. */
    public record Classification(ReceiptStatus status, double strength, List<String> indicators) {}

    private static final ReceiptStatusClassifier DEFAULT = new ReceiptStatusClassifier(ReceiptVocabulary.defaults());

    private record Phrase(String[] tokens, ReceiptStatus outcome) {}

    private final List<Phrase> phrases;
    private final List<String[]> historyMarkers;

    @Autowired
    public ReceiptStatusClassifier(ReceiptVocabularyProperties properties) {
        this(properties.vocabulary());
    }

    public ReceiptStatusClassifier(ReceiptVocabulary vocabulary) {
        List<Phrase> all = new ArrayList<>();
        add(all, vocabulary.success(), ReceiptStatus.SUCCESS_INDICATED);
        add(all, vocabulary.failed(), ReceiptStatus.FAILED_INDICATED);
        add(all, vocabulary.pending(), ReceiptStatus.PENDING_INDICATED);
        add(all, vocabulary.neutral(), null);
        // longest phrase first: it claims its words before a shorter phrase inside it can
        all.sort(Comparator.comparingInt((Phrase p) -> p.tokens().length).reversed());
        this.phrases = List.copyOf(all);
        this.historyMarkers = vocabulary.historyMarkers().stream()
                .map(ReceiptStatusClassifier::tokens)
                .filter(t -> t.length > 0)
                .toList();
    }

    /** The built-in vocabulary, for callers that have no configuration (tests, pure parsing). */
    public static ReceiptStatusClassifier withDefaults() {
        return DEFAULT;
    }

    public Classification classify(String text) {
        String[] words = tokens(text);
        int end = firstHistoryMarker(words);
        if (end < words.length) {
            words = Arrays.copyOf(words, end);
        }
        boolean[] claimed = new boolean[words.length];
        Set<ReceiptStatus> votes = new LinkedHashSet<>();
        List<String> indicators = new ArrayList<>();
        for (Phrase phrase : phrases) {
            int length = phrase.tokens().length;
            for (int i = 0; i + length <= words.length; i++) {
                if (matches(words, claimed, i, phrase.tokens())) {
                    for (int j = i; j < i + length; j++) {
                        claimed[j] = true;
                    }
                    if (phrase.outcome() != null) {
                        votes.add(phrase.outcome());
                        indicators.add(String.join(" ", phrase.tokens()));
                    }
                }
            }
        }
        if (votes.isEmpty()) {
            return new Classification(ReceiptStatus.UNKNOWN, 0.0, List.of());
        }
        if (votes.size() > 1) {
            return new Classification(ReceiptStatus.UNKNOWN, 0.3, List.copyOf(indicators)); // contradictory wording
        }
        ReceiptStatus only = votes.iterator().next();
        return new Classification(only, only == ReceiptStatus.PENDING_INDICATED ? 0.7 : 0.8, List.copyOf(indicators));
    }

    private int firstHistoryMarker(String[] words) {
        int first = words.length;
        for (String[] marker : historyMarkers) {
            for (int i = 0; i + marker.length <= first; i++) {
                if (matches(words, null, i, marker)) {
                    first = i;
                    break;
                }
            }
        }
        return first;
    }

    private static boolean matches(String[] words, boolean[] claimed, int at, String[] phrase) {
        for (int k = 0; k < phrase.length; k++) {
            if (!words[at + k].equals(phrase[k]) || (claimed != null && claimed[at + k])) {
                return false;
            }
        }
        return true;
    }

    private static void add(List<Phrase> into, List<String> entries, ReceiptStatus outcome) {
        for (String entry : entries) {
            String[] tokens = tokens(entry);
            if (tokens.length > 0) {
                into.add(new Phrase(tokens, outcome));
            }
        }
    }

    /** Lower-case words with accents, punctuation and line breaks removed. */
    static String[] tokens(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return folded.isEmpty() ? new String[0] : folded.split(" ");
    }
}
