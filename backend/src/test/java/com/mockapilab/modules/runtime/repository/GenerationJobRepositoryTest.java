package com.mockapilab.modules.runtime.repository;

import com.mockapilab.modules.auth.model.User;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GenerationJobRepositoryTest {

    @Autowired
    private GenerationJobRepository generationJobRepository;

    @Autowired
    private MockRuntimeRepository runtimeRepository;

    @Autowired
    private ContractVersionRepository contractVersionRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;
    private Project project;
    private MockRuntime runtime;

    @BeforeEach
    void setUp() {
        generationJobRepository.deleteAll();
        runtimeRepository.deleteAll();
        contractVersionRepository.deleteAll();
        contractRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("dev@example.com", "hash", "Dev User"));
        project = projectRepository.save(new Project("Test Project", "Description", user));

        Contract contract = contractRepository.save(new Contract(project, "Test Contract", "Contract Description"));
        NormalizedContract normalized = new NormalizedContract(
                new ContractMetadata("Test", "1.0", "1.0", "OpenAPI"),
                Collections.emptyList(),
                Collections.emptyMap()
        );
        ContractVersion version = contractVersionRepository.save(
                new ContractVersion(contract, 1, ContractSourceType.OPENAPI, normalized)
        );

        runtime = runtimeRepository.save(new MockRuntime(project, version, "Test Runtime", MockRuntimeStatus.RUNNING));
    }

    @Test
    @DisplayName("1. Persist and retrieve GenerationJob with timestamps and status")
    void testSaveAndRetrieveJob() {
        GenerationJob job = new GenerationJob(runtime, project.getId(), "/users", 10, 42L);
        GenerationJob saved = generationJobRepository.save(job);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(GenerationJobStatus.QUEUED);
        assertThat(saved.getCollectionPath()).isEqualTo("/users");
        assertThat(saved.getEntityCount()).isEqualTo(10);
        assertThat(saved.getRequestedSeed()).isEqualTo(42L);

        Optional<GenerationJob> found = generationJobRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRuntime().getId()).isEqualTo(runtime.getId());
    }

    @Test
    @DisplayName("2. Find job by id and runtimeId")
    void testFindByIdAndRuntimeId() {
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, project.getId(), "/pets", 5, null));

        Optional<GenerationJob> found = generationJobRepository.findByIdAndRuntimeId(job.getId(), runtime.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(job.getId());

        Optional<GenerationJob> notFound = generationJobRepository.findByIdAndRuntimeId(job.getId(), UUID.randomUUID());
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("3. Find jobs by runtimeId ordered by createdAt desc")
    void testFindByRuntimeIdOrderByCreatedAtDesc() throws InterruptedException {
        GenerationJob job1 = generationJobRepository.save(new GenerationJob(runtime, project.getId(), "/users", 5, 100L));
        Thread.sleep(10);
        GenerationJob job2 = generationJobRepository.save(new GenerationJob(runtime, project.getId(), "/orders", 10, 200L));

        List<GenerationJob> jobs = generationJobRepository.findByRuntimeIdOrderByCreatedAtDesc(runtime.getId());
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getId()).isEqualTo(job2.getId());
        assertThat(jobs.get(1).getId()).isEqualTo(job1.getId());
    }

    @Test
    @DisplayName("4. Update status transitions and completion timestamps")
    void testStatusTransitions() {
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, project.getId(), "/products", 20, 999L));

        job.setStatus(GenerationJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        GenerationJob running = generationJobRepository.save(job);
        assertThat(running.getStatus()).isEqualTo(GenerationJobStatus.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();

        running.setStatus(GenerationJobStatus.COMPLETED);
        running.setEffectiveSeed(999L);
        running.setCompletedAt(Instant.now());
        GenerationJob completed = generationJobRepository.save(running);

        assertThat(completed.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getEffectiveSeed()).isEqualTo(999L);
    }
}