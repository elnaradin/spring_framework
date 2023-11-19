package com.example.tasktracker.service;

import com.example.tasktracker.dto.task.TaskResponse;
import com.example.tasktracker.dto.task.UpsertTaskRequest;
import com.example.tasktracker.entity.Task;
import com.example.tasktracker.entity.User;
import com.example.tasktracker.exception.EntityNotFoundException;
import com.example.tasktracker.mapper.TaskMapper;
import com.example.tasktracker.repository.TaskRepository;
import com.example.tasktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {
    private final TaskRepository taskRepository;
    private final TaskMapper taskMapper;
    private final UserRepository userRepository;


    @Override
    public Flux<TaskResponse> findAll() {
        return taskRepository.findAll()
                .flatMap(this::fetchChildren)
                .map(taskMapper::taskToResponse);
    }

    @Override
    public Mono<TaskResponse> findById(String id) {
        return taskRepository
                .findById(id)
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Task not found with id:" + id)))
                .flatMap(this::fetchChildren)
                .map(taskMapper::taskToResponse);
    }

    @Override
    public Mono<TaskResponse> create(UpsertTaskRequest request) {
        Mono<Task> taskMono = Mono.just(taskMapper.requestToTask(request));
        Mono<User> authorMono = userRepository.findById(request.getAuthorId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Author not found when creating task with id:" + request.getAuthorId()
                )));
        Mono<User> assigneeMono = userRepository.findById(request.getAuthorId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Assignee not found when creating task with id:" + request.getAuthorId()
                )));
        return taskMono
                .zipWith(authorMono, (task, author) -> {
                    task.setAuthorId(author.getId());
                    return task;
                })
                .zipWith(assigneeMono, (task, assignee) -> {
                    task.setAssigneeId(assignee.getId());
                    return task;
                })
                .flatMap(taskRepository::save)
                .flatMap(this::fetchChildren)
                .map(taskMapper::taskToResponse);
    }


    @Override
    public Mono<TaskResponse> update(String id, UpsertTaskRequest request) {
        String authorId = request.getAuthorId();
        String assigneeId = request.getAssigneeId();
        Mono<Task> taskMono = taskRepository
                .findById(id)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Task not found when updating with id: " + id
                )));
        if (authorId != null) {
            Mono<User> authorMono = userRepository.findById(authorId)
                    .switchIfEmpty(Mono.error(new EntityNotFoundException(
                            "Author not found when updating task with id:" + authorId
                    )));
            taskMono = taskMono
                    .zipWith(authorMono, (task, author) -> {
                        task.setAuthorId(author.getId());
                        return task;
                    });
        }
        if (assigneeId != null) {
            Mono<User> assigneeMono = userRepository.findById(assigneeId)
                    .switchIfEmpty(Mono.error(new EntityNotFoundException(
                            "Assignee not found when updating task with id:" + assigneeId
                    )));
            taskMono = taskMono
                    .zipWith(assigneeMono, (task, assignee) -> {
                        task.setAssigneeId(assignee.getId());
                        return task;
                    });
        }
        return taskMono
                .flatMap(task -> {
                    taskMapper.update(request, task);
                    return taskRepository
                            .save(task)
                            .flatMap(this::fetchChildren)
                            .map(taskMapper::taskToResponse);
                });
    }

    @Override
    public Mono<Void> deleteById(String id) {
        return taskRepository
                .findById(id)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Task not found when deleting with id: " + id
                )))
                .flatMap(taskRepository::delete);
    }

    @Override
    public Mono<TaskResponse> addObserver(String taskId, String observerId) {
        Mono<User> userMono = userRepository.findById(observerId)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "User not found when adding observer with id: " + taskId
                )));
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Task not found when adding observer with id: " + taskId
                )))
                .zipWith(userMono, (task, user) -> {
                    task.getObserverIds().add(user.getId());
                    return task;
                })
                .flatMap(taskRepository::save)
                .flatMap(this::fetchChildren)
                .map(taskMapper::taskToResponse);
    }

    @Override
    public Mono<TaskResponse> removeObserver(String taskId, String observerId) {
        Mono<User> userMono = userRepository.findById(observerId)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "User not found when removing observer with id: " + taskId
                )));
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Task not found when removing observer with id: " + taskId
                )))
                .zipWith(userMono, (task, user) -> {
                    Set<String> observers = task.getObserverIds();
                    if (observers == null || observers.stream().noneMatch(id -> id.equals(user.getId()))) {
                        throw new EntityNotFoundException("Task doesn't contain observer with id: " + user.getId());
                    }
                    task.getObserverIds().remove(user.getId());
                    return task;
                })
                .flatMap(taskRepository::save)
                .flatMap(this::fetchChildren)
                .map(taskMapper::taskToResponse);
    }

    private Mono<? extends Task> fetchChildren(Task initialTask) {
        Mono<User> authorMono = userRepository.findById(initialTask.getAuthorId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Author not found with id: " + initialTask.getAuthorId()
                )));
        Mono<User> assigneeMono = userRepository.findById(initialTask.getAssigneeId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "Assignee not found with id: " + initialTask.getAssigneeId()
                )));
        Flux<User> observersFlux;
        if (initialTask.getObserverIds() != null) {
            observersFlux = userRepository.findAllById(initialTask.getObserverIds());
        } else {
            observersFlux = Flux.fromIterable(Collections.emptySet());
        }
        return Mono.just(initialTask)
                .zipWith(authorMono, (task, author) -> {
                    task.setAuthor(author);
                    return task;
                })
                .zipWith(assigneeMono, (task, assignee) -> {
                    task.setAssignee(assignee);
                    return task;
                })
                .zipWith(observersFlux.collectList(), (task, observers) -> {
                    task.setObservers(new HashSet<>(observers));
                    return task;
                });
    }
}
