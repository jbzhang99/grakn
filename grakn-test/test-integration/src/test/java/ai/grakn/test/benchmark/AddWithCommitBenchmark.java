package ai.grakn.test.benchmark;

import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Entity;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.Role;
import ai.grakn.test.rule.SessionContext;
import org.junit.Rule;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;


@State(Scope.Benchmark)
public class AddWithCommitBenchmark extends BenchmarkTest {

    @Rule
    public final SessionContext sessionContext = SessionContext.create();

    private GraknSession session;
    private EntityType entityType;
    private RelationshipType relationshipType;
    private Role role1;
    private Role role2;

    @Setup
    public void setup() throws Throwable {
        session = sessionContext.newSession();
        try(GraknTx tx = session.open(GraknTxType.WRITE)) {
            role1 = tx.putRole("benchmark_role1");
            role2 = tx.putRole("benchmark_role2");
            entityType = tx.putEntityType("benchmark_Entitytype").plays(role1).plays(role2);
            relationshipType = tx.putRelationshipType("benchmark_relationshipType").relates(role1).relates(role2);
            tx.commit();
        }
    }

    @Benchmark
    public void addEntity() {
        try(GraknTx graph = session.open(GraknTxType.WRITE)) {
            entityType.addEntity();
            graph.commit();
        }
    }

    @Benchmark
    public void addRelation() {
        try(GraknTx graph = session.open(GraknTxType.WRITE)) {
            Entity entity1 = entityType.addEntity();
            Entity entity2 = entityType.addEntity();
            relationshipType.addRelationship().addRolePlayer(role1, entity1).addRolePlayer(role2, entity2);
            graph.commit();
        }
    }
}
