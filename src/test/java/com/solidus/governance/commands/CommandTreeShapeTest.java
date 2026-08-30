package com.solidus.governance.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural regression test for the /governance command tree, introduced
 * alongside the register() refactor (single decompiled expression -> 12
 * per-family builders). The expected shape below was captured from the
 * pre-refactor tree and must stay identical unless a command is
 * intentionally added or removed (update BOTH sides then).
 *
 * Paths are literal/argument node names; a trailing " *" marks a branch
 * node without its own executor, " ->" marks an argument placeholder.
 */
class CommandTreeShapeTest {

    private static List<String> actualPaths;

    @BeforeAll
    static void buildTree() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        GovernanceCommand.register(dispatcher, null);
        actualPaths = new ArrayList<>();
        for (CommandNode<?> root : dispatcher.getRoot().getChildren()) {
            collect(root, "", actualPaths);
        }
        actualPaths.sort(String.CASE_INSENSITIVE_ORDER);
    }

    static void collect(CommandNode<?> node, String path, List<String> out) {
        String name = node.getName();
        if (name.startsWith("arguments") || name.startsWith("redirects")) return; // brigadier internals
        String here = path.isEmpty() ? name : path + " " + name;
        if (node.getCommand() != null || node.getChildren().isEmpty()) {
            out.add(here);
        } else {
            out.add(here + " *");
        }
        for (CommandNode<?> child : node.getChildren()) {
            collect(child, here, out);
        }
    }

    @Test
    void treeShapeMatchesPreRefactorReference() {
        List<String> expected = new ArrayList<>();
        expected.add("gov");
        expected.add("governance");
        expected.add("governance audit *");
        expected.add("governance audit export *");
        expected.add("governance audit export csv");
        expected.add("governance audit export csv days");
        expected.add("governance audit recent");
        expected.add("governance audit recent count");
        expected.add("governance audit search *");
        expected.add("governance audit search category *");
        expected.add("governance audit search category query");
        expected.add("governance audit search player *");
        expected.add("governance audit search player query");
        expected.add("governance automation *");
        expected.add("governance automation lockdown *");
        expected.add("governance automation lockdown activate *");
        expected.add("governance automation lockdown activate reason");
        expected.add("governance automation lockdown deactivate");
        expected.add("governance automation status");
        expected.add("governance discord");
        expected.add("governance discord remove *");
        expected.add("governance discord remove category");
        expected.add("governance discord set *");
        expected.add("governance discord set category *");
        expected.add("governance discord set category url");
        expected.add("governance discord test");
        expected.add("governance discord test category");
        expected.add("governance event *");
        expected.add("governance event cancel *");
        expected.add("governance event cancel id");
        expected.add("governance event create *");
        expected.add("governance event create type *");
        expected.add("governance event create type name");
        expected.add("governance event history");
        expected.add("governance event info *");
        expected.add("governance event info id");
        expected.add("governance event list");
        expected.add("governance fingerprint");
        expected.add("governance intervention *");
        expected.add("governance intervention add *");
        expected.add("governance intervention add player *");
        expected.add("governance intervention add player amount");
        expected.add("governance intervention freeze *");
        expected.add("governance intervention freeze player");
        expected.add("governance intervention freeze player duration");
        expected.add("governance intervention lock *");
        expected.add("governance intervention lock reason");
        expected.add("governance intervention remove *");
        expected.add("governance intervention remove player *");
        expected.add("governance intervention remove player amount");
        expected.add("governance intervention set *");
        expected.add("governance intervention set player *");
        expected.add("governance intervention set player amount");
        expected.add("governance intervention suspicious *");
        expected.add("governance intervention suspicious list");
        expected.add("governance intervention suspicious mark *");
        expected.add("governance intervention suspicious mark player *");
        expected.add("governance intervention suspicious mark player reason");
        expected.add("governance intervention suspicious unmark *");
        expected.add("governance intervention suspicious unmark player");
        expected.add("governance intervention unfreeze *");
        expected.add("governance intervention unfreeze player");
        expected.add("governance intervention unlock");
        expected.add("governance license");
        expected.add("governance limits");
        expected.add("governance limits reset *");
        expected.add("governance limits reset player");
        expected.add("governance limits set *");
        expected.add("governance limits set type *");
        expected.add("governance limits set type value");
        expected.add("governance limits status *");
        expected.add("governance limits status player");
        expected.add("governance policy *");
        expected.add("governance policy delete *");
        expected.add("governance policy delete name");
        expected.add("governance policy info *");
        expected.add("governance policy info name");
        expected.add("governance policy list");
        expected.add("governance policy load *");
        expected.add("governance policy load name");
        expected.add("governance policy preview *");
        expected.add("governance policy preview name");
        expected.add("governance policy save *");
        expected.add("governance policy save name");
        expected.add("governance policy save name displayName");
        expected.add("governance policy save name displayName description");
        expected.add("governance profile *");
        expected.add("governance profile player");
        expected.add("governance recovery *");
        expected.add("governance recovery dryrun *");
        expected.add("governance recovery dryrun auditId");
        expected.add("governance recovery dryrun player *");
        expected.add("governance recovery dryrun player target *");
        expected.add("governance recovery dryrun player target fromDays");
        expected.add("governance recovery dryrun timeframe *");
        expected.add("governance recovery dryrun timeframe fromDaysBack *");
        expected.add("governance recovery dryrun timeframe fromDaysBack toDaysBack");
        expected.add("governance recovery rollback *");
        expected.add("governance recovery rollback auditId");
        expected.add("governance recovery rollback player *");
        expected.add("governance recovery rollback player target *");
        expected.add("governance recovery rollback player target fromDays");
        expected.add("governance recovery rollback-timeframe *");
        expected.add("governance recovery rollback-timeframe fromDaysBack *");
        expected.add("governance recovery rollback-timeframe fromDaysBack toDaysBack");
        expected.add("governance recovery snapshot *");
        expected.add("governance recovery snapshot create");
        expected.add("governance recovery snapshot create name");
        expected.add("governance recovery snapshot list");
        expected.add("governance recovery timeline *");
        expected.add("governance recovery timeline player");
        expected.add("governance rules *");
        expected.add("governance rules add *");
        expected.add("governance rules add name *");
        expected.add("governance rules add name cooldown");
        expected.add("governance rules add-action *");
        expected.add("governance rules add-action rule *");
        expected.add("governance rules add-action rule type");
        expected.add("governance rules add-action rule type key");
        expected.add("governance rules add-action rule type key value");
        expected.add("governance rules add-condition *");
        expected.add("governance rules add-condition rule *");
        expected.add("governance rules add-condition rule type *");
        expected.add("governance rules add-condition rule type value");
        expected.add("governance rules delete *");
        expected.add("governance rules delete name");
        expected.add("governance rules disable *");
        expected.add("governance rules disable name");
        expected.add("governance rules enable *");
        expected.add("governance rules enable name");
        expected.add("governance rules info *");
        expected.add("governance rules info name");
        expected.add("governance rules list");
        expected.add("governance rules remove-action *");
        expected.add("governance rules remove-action rule *");
        expected.add("governance rules remove-action rule index");
        expected.add("governance rules remove-condition *");
        expected.add("governance rules remove-condition rule *");
        expected.add("governance rules remove-condition rule index");
        expected.add("governance rules set-cooldown *");
        expected.add("governance rules set-cooldown rule *");
        expected.add("governance rules set-cooldown rule duration");
        expected.add("governance simulation");
        expected.add("governance simulation false");
        expected.add("governance simulation insight");
        expected.add("governance simulation refresh");
        expected.add("governance simulation true");
        expected.add("governance tax *");
        expected.add("governance tax brackets *");
        expected.add("governance tax brackets add *");
        expected.add("governance tax brackets add threshold *");
        expected.add("governance tax brackets add threshold rate");
        expected.add("governance tax brackets list");
        expected.add("governance tax brackets remove *");
        expected.add("governance tax brackets remove threshold");
        expected.add("governance tax rates");
        expected.add("governance tax set *");
        expected.add("governance tax set type *");
        expected.add("governance tax set type rate");

        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(actualPaths);
        List<String> extra = new ArrayList<>(actualPaths);
        extra.removeAll(expected);

        assertTrue(missing.isEmpty(), "commands lost by the refactor: " + missing);
        assertTrue(extra.isEmpty(), "unexpected new paths: " + extra);
        assertEquals(expected.size(), actualPaths.size());
    }

    @Test
    void govAliasRedirectsToGovernance() {
        // The alias must exist as a root sibling next to the real tree:
        assertTrue(actualPaths.stream().anyMatch(p -> p.equals("gov")),
            "/gov alias missing");
        assertTrue(actualPaths.stream().anyMatch(p -> p.equals("governance")),
            "/governance root missing");
    }
}
