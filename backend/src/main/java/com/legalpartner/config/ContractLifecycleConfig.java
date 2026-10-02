package com.legalpartner.config;

import com.legalpartner.model.enums.ContractStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contract lifecycle state machine from {@code config/contract_lifecycle.yml}. */
@Component
@Slf4j
public class ContractLifecycleConfig {

    private static final String CONFIG_PATH = "config/contract_lifecycle.yml";

    private ContractStatus initial = ContractStatus.DRAFT;
    private Map<ContractStatus, Set<ContractStatus>> transitions = Map.of();
    private Set<ContractStatus> lockOn = Set.of();
    private Set<ContractStatus> unlockOn = Set.of();
    private Set<ContractStatus> finalizeFrom = Set.of();
    private ContractStatus finalizeTo = ContractStatus.PENDING_SIGNATURE;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (in == null) throw new IllegalStateException("Missing " + CONFIG_PATH);
            Map<String, Object> root = new Yaml().load(in);
            this.initial = ContractStatus.valueOf(String.valueOf(root.get("initial")));
            Map<ContractStatus, Set<ContractStatus>> t = new EnumMap<>(ContractStatus.class);
            ((Map<String, Object>) root.getOrDefault("transitions", Map.of()))
                    .forEach((from, to) -> t.put(ContractStatus.valueOf(from), statuses(to)));
            this.transitions = Collections.unmodifiableMap(t);
            this.lockOn = statuses(root.get("lock_on"));
            this.unlockOn = statuses(root.get("unlock_on"));
            Map<String, Object> fin = (Map<String, Object>) root.getOrDefault("finalize", Map.of());
            this.finalizeFrom = statuses(fin.get("allowed_from"));
            this.finalizeTo = ContractStatus.valueOf(String.valueOf(fin.get("to")));
            if (transitions.isEmpty()) throw new IllegalStateException(CONFIG_PATH + " has no transitions");
            log.info("ContractLifecycleConfig: {} statuses with transitions", transitions.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    private static Set<ContractStatus> statuses(Object o) {
        EnumSet<ContractStatus> out = EnumSet.noneOf(ContractStatus.class);
        if (o instanceof List<?> l) for (Object s : l) out.add(ContractStatus.valueOf(String.valueOf(s)));
        return Collections.unmodifiableSet(out);
    }

    public ContractStatus initial() { return initial; }

    public Set<ContractStatus> nextStatuses(ContractStatus from) {
        if (from == null) return Set.of(initial);
        return transitions.getOrDefault(from, Set.of());
    }

    public boolean locks(ContractStatus s) { return lockOn.contains(s); }
    public boolean unlocks(ContractStatus s) { return unlockOn.contains(s); }
    public boolean canFinalizeFrom(ContractStatus s) { return s == null || finalizeFrom.contains(s); }
    public ContractStatus finalizeTo() { return finalizeTo; }
    public Map<ContractStatus, Set<ContractStatus>> transitions() { return transitions; }
}
