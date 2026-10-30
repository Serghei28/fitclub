package com.fitclub.schedule.api;

import java.util.List;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.schedule.api.dto.ScheduleDtos.CreateTrainingTypeRequest;
import com.fitclub.schedule.api.dto.ScheduleDtos.TrainingTypeResponse;
import com.fitclub.schedule.application.ScheduleCommandService;
import com.fitclub.schedule.application.ScheduleQueryService;

@RestController
@RequestMapping("/api/training-types")
@Tag(name = "Типы тренировок")
class TrainingTypeController {

    private final ScheduleQueryService queries;
    private final ScheduleCommandService commands;

    TrainingTypeController(ScheduleQueryService queries, ScheduleCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    @Operation(summary = "Активные типы тренировок")
    List<TrainingTypeResponse> list() {
        return queries.activeTrainingTypes().stream().map(TrainingTypeResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создание типа тренировки (ADMIN)")
    TrainingTypeResponse create(@Valid @RequestBody CreateTrainingTypeRequest request) {
        return TrainingTypeResponse.from(commands.createTrainingType(request.name(), request.description()));
    }
}
