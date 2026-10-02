package com.legalpartner.service.learning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * MinHash signatures + banded LSH + union-find for clustering near-duplicate clauses.
 *
 * {@code numHashes} hash functions over {@code shingleWords}-word shingles; LSH with
 * {@code bands} bands proposes candidate pairs, which are then verified with the
 * signature-estimated Jaccard against the caller's threshold. Deterministic for a given seed.
 * Parameters come from {@code learning.yml mining.minhash}; changing them invalidates stored
 * signatures (documents must be re-observed).
 */
public final class MinHasher {

    private static final long PRIME = 2_147_483_647L; // 2^31 - 1

    private final int numHashes;
    private final int bands;
    private final int rows;
    private final int shingleWords;
    private final long[] a;
    private final long[] b;

    public MinHasher(int numHashes, int bands, int shingleWords, long seed) {
        if (numHashes <= 0 || bands <= 0 || numHashes % bands != 0) {
            throw new IllegalArgumentException("numHashes must be a positive multiple of bands");
        }
        this.numHashes = numHashes;
        this.bands = bands;
        this.rows = numHashes / bands;
        this.shingleWords = shingleWords;
        this.a = new long[numHashes];
        this.b = new long[numHashes];
        Random r = new Random(seed);
        for (int i = 0; i < numHashes; i++) {
            a[i] = 1 + (long) (r.nextDouble() * (PRIME - 2));
            b[i] = (long) (r.nextDouble() * (PRIME - 1));
        }
    }

    public static MinHasher from(LearningConfig c) {
        return new MinHasher(c.getMinhashNumHashes(), c.getMinhashBands(), c.getMinhashShingleWords(), c.getMinhashSeed());
    }

    public int numHashes() { return numHashes; }

    public int[] signature(String text) {
        return signature(TextSimilarity.shingles(text, shingleWords));
    }

    public int[] signature(Set<String> shingles) {
        int[] sig = new int[numHashes];
        Arrays.fill(sig, Integer.MAX_VALUE);
        for (String s : shingles) {
            long x = stableHash(s);
            for (int i = 0; i < numHashes; i++) {
                int h = (int) ((a[i] * x + b[i]) % PRIME);
                if (h < sig[i]) sig[i] = h;
            }
        }
        return sig;
    }

    /** Fraction of equal positions — an unbiased estimate of Jaccard similarity. */
    public static double estimate(int[] x, int[] y) {
        int n = Math.min(x.length, y.length);
        if (n == 0) return 0;
        int eq = 0;
        for (int i = 0; i < n; i++) if (x[i] == y[i]) eq++;
        return (double) eq / n;
    }

    public static String encode(int[] sig) {
        StringBuilder sb = new StringBuilder(sig.length * 11);
        for (int i = 0; i < sig.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(sig[i]);
        }
        return sb.toString();
    }

    public static int[] decode(String s) {
        String[] parts = s.split(",");
        int[] sig = new int[parts.length];
        for (int i = 0; i < parts.length; i++) sig[i] = Integer.parseInt(parts[i].trim());
        return sig;
    }

    /**
     * Clusters of indexes into {@code signatures} whose members are near-duplicates.
     * Signatures of a different length (made with other parameters) are left as singletons.
     */
    public List<List<Integer>> cluster(List<int[]> signatures, double threshold) {
        int n = signatures.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int band = 0; band < bands; band++) {
            Map<String, List<Integer>> buckets = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int[] s = signatures.get(i);
                if (s.length != numHashes) continue;
                StringBuilder key = new StringBuilder();
                for (int r = 0; r < rows; r++) key.append(s[band * rows + r]).append(':');
                buckets.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> bucket : buckets.values()) {
                for (int x = 0; x < bucket.size(); x++) {
                    for (int y = x + 1; y < bucket.size(); y++) {
                        int i = bucket.get(x), j = bucket.get(y);
                        if (find(parent, i) != find(parent, j)
                                && estimate(signatures.get(i), signatures.get(j)) >= threshold) {
                            parent[find(parent, i)] = find(parent, j);
                        }
                    }
                }
            }
        }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        List<List<Integer>> out = new ArrayList<>(groups.values());
        out.sort((x, y) -> Integer.compare(y.size(), x.size()));
        return out;
    }

    /** Member with the highest mean estimated similarity to the rest of its cluster. */
    public static int medoid(List<Integer> cluster, List<int[]> signatures) {
        int best = cluster.get(0);
        double bestScore = -1;
        for (int i : cluster) {
            double sum = 0;
            for (int j : cluster) if (i != j) sum += estimate(signatures.get(i), signatures.get(j));
            if (sum > bestScore) { bestScore = sum; best = i; }
        }
        return best;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** FNV-1a 64-bit over UTF-8, folded to 31 bits — stable across JVMs (unlike String.hashCode seeds). */
    static long stableHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (x & 0xff);
            h *= 0x100000001b3L;
        }
        return (h ^ (h >>> 31)) & 0x7fffffffL;
    }
}
