package com.vendorflow.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Architecture rules from docs/ARCHITECTURE.md and CLAUDE.md, enforced at build time (docs/DECISIONS.md, 2026-10-04). Pure bytecode
 * analysis of production classes: no Spring context, no database, runs in about a second.
 */
class ArchitectureTest {

    private static final String ROOT = "com.vendorflow";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        // Guard against a vacuous pass (wrong package, empty import).
        assertThat(production.size()).isGreaterThan(100);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** "com.vendorflow.vendor.api.X" -> "vendor"; the root package and "shared" are not features (null). */
    private static String featureOf(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith(ROOT + ".")) {
            return null;
        }
        String feature = pkg.substring(ROOT.length() + 1).split("\\.")[0];
        return feature.equals("shared") ? null : feature;
    }

    private static boolean isRepository(JavaClass c) {
        return c.getSimpleName().endsWith("Repository")
                && (c.isInterface() || c.isAnnotatedWith(Repository.class));
    }

    private static boolean isEntity(JavaClass c) {
        return c.isAnnotatedWith(jakarta.persistence.Entity.class);
    }

    private static boolean isController(JavaClass c) {
        return c.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                || c.isAnnotatedWith(org.springframework.stereotype.Controller.class);
    }

    // ---- (a) repositories are private to their feature ----------------------------------------------------------

    @Test
    void repositoriesAreOnlyUsedByTheirOwnFeature() {
        List<String> violations = new ArrayList<>();
        int repositories = 0;
        for (JavaClass repo : production) {
            if (!isRepository(repo)) {
                continue;
            }
            repositories++;
            String owner = featureOf(repo);
            assertThat(owner).as("repository %s must live in a feature package", repo.getName()).isNotNull();
            for (Dependency d : repo.getDirectDependenciesToSelf()) {
                if (!owner.equals(featureOf(d.getOriginClass()))) {
                    violations.add(d.getDescription());
                }
            }
        }
        assertThat(repositories).isGreaterThan(8);
        assertThat(violations)
                .as("Another feature must call the owning feature's application service, never its repository")
                .isEmpty();
    }

    @Test
    void apiAndDomainLayersDoNotUseRepositories() {
        noClasses().that().resideInAPackage("..api..").or().resideInAPackage("..domain..")
                .should().dependOnClassesThat(describe("are repositories", ArchitectureTest::isRepository))
                .because("controllers are thin and the domain has no persistence access (CLAUDE.md)")
                .check(production);
    }

    // ---- (b) controllers --------------------------------------------------------------------------------------

    @Test
    void controllersDoNotUseRepositoriesOrEntities() {
        noClasses().that(describe("are controllers", ArchitectureTest::isController))
                .should().dependOnClassesThat(describe("are repositories", ArchitectureTest::isRepository))
                .orShould().dependOnClassesThat(describe("are JPA entities", ArchitectureTest::isEntity))
                .because("controllers speak DTOs and call application services; entities never leave the "
                        + "application layer")
                .check(production);
    }

    // ---- (c) nothing in the API layer exposes an entity ----------------------------------------------------------

    @Test
    void apiLayerNeverReferencesEntities() {
        // Every class in an "api" package: controllers AND their request/response records. A response record that
        // holds an entity would serialize it; a request record that embeds one is mass assignment. The only allowed
        // contact is a static mapper named "from" (DocumentTypeView.from(DocumentType)), called by application
        // services, which is how an entity becomes a DTO without leaving the application layer.
        List<String> violations = new ArrayList<>();
        for (JavaClass c : production) {
            if (!c.getPackageName().contains(".api")) {
                continue;
            }
            c.getFields().stream()
                    .filter(f -> f.getType().getAllInvolvedRawTypes().stream().anyMatch(ArchitectureTest::isEntity))
                    .forEach(f -> violations.add("field " + f.getFullName()));
            for (JavaMethod m : c.getMethods()) {
                boolean mapper = m.getName().equals("from") && m.getModifiers().contains(JavaModifier.STATIC);
                boolean returnsEntity = m.getReturnType().getAllInvolvedRawTypes().stream()
                        .anyMatch(ArchitectureTest::isEntity);
                boolean takesEntity = m.getParameters().stream().anyMatch(p -> p.getType().getAllInvolvedRawTypes()
                        .stream().anyMatch(ArchitectureTest::isEntity));
                if (returnsEntity || (takesEntity && !mapper)) {
                    violations.add("method " + m.getFullName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void apiLayerEntityMappersAreTheOnlyEntityAccess() {
        // Guards the exception above: outside the "from" mappers, api classes must not call into an entity at all.
        List<String> violations = new ArrayList<>();
        for (JavaClass c : production) {
            if (!c.getPackageName().contains(".api")) {
                continue;
            }
            c.getMethodCallsFromSelf().stream()
                    .filter(call -> isEntity(call.getTargetOwner()) && !call.getOrigin().getName().equals("from"))
                    .forEach(call -> violations.add(call.getDescription()));
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void controllerMethodSignaturesOnlyUseNonEntityTypes() {
        // The rule above is by class; this one is explicit about method signatures including generics
        // (ResponseEntity<Page<X>>), which is the "entity returned from a controller" failure mode.
        List<String> violations = new ArrayList<>();
        int controllerMethods = 0;
        for (JavaClass c : production) {
            if (!isController(c)) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                controllerMethods++;
                Set<JavaClass> used = new HashSet<>(m.getRawParameterTypes());
                used.add(m.getRawReturnType());
                used.addAll(m.getReturnType().getAllInvolvedRawTypes());
                m.getParameters().forEach(p -> used.addAll(p.getType().getAllInvolvedRawTypes()));
                used.stream().filter(ArchitectureTest::isEntity)
                        .forEach(e -> violations.add(m.getFullName() + " uses entity " + e.getName()));
            }
        }
        assertThat(controllerMethods).isGreaterThan(20);
        assertThat(violations).isEmpty();
    }

    // ---- (d) tenant isolation of repository methods ------------------------------------------------------------

    /**
     * Repository methods that legitimately do NOT take an organization id, with the reason. Everything else declared
     * on a repository must have an {@code organizationId} parameter. Adding an entry is a security decision: it
     * must be justified here and reviewed. Key: {@code SimpleClassName.method}, or {@code SimpleClassName.*} for a
     * repository of a global (non tenant-scoped) table.
     */
    private static final Map<String, String> UNSCOPED_REPOSITORY_METHODS = Map.ofEntries(
            Map.entry("AppUserRepository.*", "app_user is global: a user exists outside any organization"),
            Map.entry("UserTokenRepository.*", "user_token belongs to a user (verification/reset), not an "
                    + "organization; lookup is by the hash of an unguessable token"),
            Map.entry("OrganizationRepository.*", "organization IS the tenant; its id is the tenant id itself"),
            Map.entry("InvitationRepository.findByTokenHash", "public accept flow: the invitee is not a member yet; "
                    + "the unguessable token is the capability and determines the organization"),
            Map.entry("InvitationRepository.findByTokenHashForUpdate", "same as findByTokenHash (row lock for accept)"),
            Map.entry("MembershipRepository.findAllSummariesByUserId", "lists the caller's own organizations: scoped "
                    + "by the authenticated user id, which is the point (organization switcher)"),
            Map.entry("NotificationRepository.claimDue", "system outbox dispatcher: not request scoped, rows carry "
                    + "their own recipient"),
            Map.entry("NotificationRepository.findByIdempotencyKey", "system outbox: idempotency keys are globally "
                    + "unique and embed the org/entity"),
            Map.entry("NotificationRepository.scrubUndeliveredTokens", "system retention job over all organizations"),
            Map.entry("SubscriptionRepository.findByStripeCustomerIdForUpdate", "signed Stripe webhook: the customer "
                    + "id from the verified event determines the organization"),
            Map.entry("VendorImportRepository.deleteExpiredBefore", "system retention job over all organizations"));

    /** Inherited CRUD methods that take only the primary key (or nothing): banned on tenant repositories. */
    private static final Set<String> UNSCOPED_INHERITED = Set.of("findById", "getById", "getReferenceById",
            "findOne", "existsById", "deleteById", "findAllById", "deleteAllById", "findAll", "deleteAll",
            "deleteAllInBatch", "deleteAllByIdInBatch", "count");

    private static boolean isGlobalRepository(JavaClass repo) {
        return UNSCOPED_REPOSITORY_METHODS.containsKey(repo.getSimpleName() + ".*");
    }

    @Test
    void everyDeclaredRepositoryMethodIsScopedByOrganizationIdOrExplicitlyAllowlisted() {
        List<String> violations = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JavaClass repo : production) {
            if (!isRepository(repo)) {
                continue;
            }
            if (isGlobalRepository(repo)) {
                used.add(repo.getSimpleName() + ".*");
                continue;
            }
            for (JavaMethod m : repo.getMethods()) {
                boolean apiMethod = repo.isInterface() || m.getModifiers().contains(JavaModifier.PUBLIC);
                if (!apiMethod || m.getModifiers().contains(JavaModifier.STATIC)
                        || m.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                    continue; // helpers of @Repository classes, not part of the API
                }
                String key = repo.getSimpleName() + "." + m.getName();
                if (UNSCOPED_REPOSITORY_METHODS.containsKey(key)) {
                    used.add(key);
                } else if (!hasOrganizationIdParameter(m)) {
                    violations.add(key + ": no organizationId parameter (tenant data must be scoped; if the table is "
                            + "global add a justified entry to UNSCOPED_REPOSITORY_METHODS)");
                }
            }
        }
        assertThat(violations).isEmpty();
        assertThat(used).as("stale allowlist entries (method renamed or removed)")
                .containsAll(UNSCOPED_REPOSITORY_METHODS.keySet());
    }

    private static boolean hasOrganizationIdParameter(JavaMethod m) {
        // Names come from the "-parameters" compiler flag (Spring Boot parent enables it) or from @Param.
        for (JavaParameter p : m.getParameters()) {
            if (p.isAnnotatedWith(Param.class)
                    && "organizationId".equals(p.getAnnotationOfType(Param.class).value())) {
                return true;
            }
        }
        for (java.lang.reflect.Parameter p : m.reflect().getParameters()) {
            if (p.getName().equals("organizationId")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void tenantRepositoriesAreNeverCalledThroughUnscopedInheritedMethods() {
        Set<String> violations = new TreeSet<>();
        for (JavaClass repo : production) {
            if (!isRepository(repo) || isGlobalRepository(repo)) {
                continue;
            }
            for (Dependency d : repo.getDirectDependenciesToSelf()) {
                d.getOriginClass().getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().getName().equals(repo.getName())
                                && UNSCOPED_INHERITED.contains(call.getTarget().getName()))
                        .forEach(call -> violations.add(call.getDescription()));
            }
        }
        assertThat(violations)
                .as("Use the organization-scoped repository methods, not findById/findAll/... on tenant repositories")
                .isEmpty();
    }

    // ---- (e) no package cycles between features -----------------------------------------------------------------

    /**
     * Feature cycles that predate this test and are accepted debt (docs/PROJECT_STATE.md). The test fails if a NEW
     * cycle appears AND if one of these disappears (then delete it here: the ratchet only tightens). audit has been
     * cycle-free since TenantContext and Role moved to shared.tenant.
     */
    private static final Set<Set<String>> KNOWN_CYCLES = Set.of(
            new TreeSet<>(Set.of("identity", "notification", "organization")),
            new TreeSet<>(Set.of("compliance", "document", "vendor")));

    @Test
    void featurePackagesHaveNoNewCycles() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (JavaClass c : production) {
            String from = featureOf(c);
            if (from == null) {
                continue;
            }
            graph.computeIfAbsent(from, k -> new TreeSet<>());
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                String to = featureOf(d.getTargetClass());
                if (to != null && !to.equals(from)) {
                    graph.get(from).add(to);
                }
            }
        }
        Set<Set<String>> cycles = new HashSet<>();
        for (String a : graph.keySet()) {
            Set<String> component = new TreeSet<>();
            component.add(a);
            for (String b : graph.keySet()) {
                if (!a.equals(b) && reaches(graph, a, b) && reaches(graph, b, a)) {
                    component.add(b);
                }
            }
            if (component.size() > 1) {
                cycles.add(component);
            }
        }
        assertThat(cycles).as("strongly connected feature groups (dependency graph: %s)", graph)
                .isEqualTo(KNOWN_CYCLES);
    }

    private static boolean reaches(Map<String, Set<String>> graph, String from, String to) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(List.of(from));
        while (!stack.isEmpty()) {
            String n = stack.remove(stack.size() - 1);
            for (String next : graph.getOrDefault(n, Set.of())) {
                if (next.equals(to)) {
                    return true;
                }
                if (seen.add(next)) {
                    stack.add(next);
                }
            }
        }
        return false;
    }

    @Test
    void sharedPackageDoesNotDependOnFeatures() {
        // shared is the bottom layer: if it pointed at a feature, every feature would be in a cycle through it.
        noClasses().that().resideInAPackage(ROOT + ".shared..")
                .should().dependOnClassesThat(describe("are in a feature package", c -> featureOf(c) != null))
                .check(production);
    }

    @Test
    void auditOnlyDependsOnShared() {
        noClasses().that().resideInAPackage(ROOT + ".audit..")
                .should().dependOnClassesThat(describe("are in another feature",
                        c -> featureOf(c) != null && !featureOf(c).equals("audit")))
                .because("audit is called by everyone; it may only use shared (TenantContext lives in shared.tenant)")
                .check(production);
    }
}
