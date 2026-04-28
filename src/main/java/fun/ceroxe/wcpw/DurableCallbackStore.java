package fun.ceroxe.wcpw;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DurableCallbackStore {
    private static final Logger logger = LoggerFactory.getLogger(DurableCallbackStore.class);
    private static final Gson gson = new Gson();
    private final Path queueDir;

    public DurableCallbackStore() {
        this(Path.of("callback_queue"));
    }

    DurableCallbackStore(Path queueDir) {
        this.queueDir = queueDir;
        try {
            Files.createDirectories(queueDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建回调持久化目录: " + queueDir.toAbsolutePath(), e);
        }
    }

    public void save(DTOs.DurableCallbackTask task) throws IOException {
        if (task == null || task.recordId() == null || task.recordId().isBlank()) {
            throw new IllegalArgumentException("持久回调任务缺少 recordId");
        }

        Path target = fileFor(task.recordId());
        Path tmp = queueDir.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(tmp, gson.toJson(task), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            moveIntoPlace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public List<DTOs.DurableCallbackTask> loadAll() {
        List<DTOs.DurableCallbackTask> tasks = new ArrayList<>();
        try (var stream = Files.list(queueDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> loadOne(path, tasks));
        } catch (IOException e) {
            logger.error("扫描持久回调队列失败", e);
        }
        return tasks;
    }

    public void delete(DTOs.DurableCallbackTask task) {
        if (task == null) return;
        try {
            Files.deleteIfExists(fileFor(resolveRecordId(task)));
        } catch (IOException e) {
            logger.error("[{}] 删除持久回调记录失败", task.taskId(), e);
        }
    }

    private void loadOne(Path path, List<DTOs.DurableCallbackTask> tasks) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            DTOs.DurableCallbackTask task = gson.fromJson(json, DTOs.DurableCallbackTask.class);
            if (task != null && task.taskId() != null && task.callbackUrl() != null && task.payload() != null) {
                tasks.add(ensureRecordId(task, path));
            } else {
                logger.error("持久回调记录无效: {}", path);
            }
        } catch (Exception e) {
            logger.error("读取持久回调记录失败: {}", path, e);
        }
    }

    private Path fileFor(String taskId) {
        String safeName = taskId == null ? "unknown" : taskId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return queueDir.resolve(safeName + ".json");
    }

    private void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target);
        }
    }

    private DTOs.DurableCallbackTask ensureRecordId(DTOs.DurableCallbackTask task, Path sourcePath) {
        if (task.recordId() != null && !task.recordId().isBlank()) {
            return task;
        }
        return new DTOs.DurableCallbackTask(
                stripJsonSuffix(sourcePath.getFileName().toString()),
                task.taskId(),
                task.callbackUrl(),
                task.payload()
        );
    }

    private String resolveRecordId(DTOs.DurableCallbackTask task) {
        if (task.recordId() != null && !task.recordId().isBlank()) {
            return task.recordId();
        }
        return task.taskId();
    }

    private String stripJsonSuffix(String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
