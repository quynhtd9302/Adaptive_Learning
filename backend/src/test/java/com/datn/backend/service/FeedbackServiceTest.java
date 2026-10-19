package com.datn.backend.service;

import com.datn.backend.dto.FeedbackRequest;
import com.datn.backend.dto.FeedbackResponse;
import com.datn.backend.entity.Feedback;
import com.datn.backend.entity.FocusLog;
import com.datn.backend.entity.Lesson;
import com.datn.backend.entity.QuizAttempt;
import com.datn.backend.entity.User;
import com.datn.backend.repository.FeedbackRepository;
import com.datn.backend.repository.FocusLogRepository;
import com.datn.backend.repository.LessonRepository;
import com.datn.backend.repository.QuizAttemptRepository;
import com.datn.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 19, 9, 0);

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    @Mock
    private FocusLogRepository focusLogRepository;

    private FeedbackService feedbackService;
    private User user;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(
                feedbackRepository,
                userRepository,
                lessonRepository,
                quizAttemptRepository,
                focusLogRepository
        );
        user = new User();
        user.setId(1L);
        lesson = new Lesson();
        lesson.setId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(lessonRepository.findById(1L)).thenReturn(Optional.of(lesson));
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void focusScoreUsesLogsRecordedWhileWatchingLessonBeforeQuiz() {
        QuizAttempt attempt = attempt(5L, BASE.plusMinutes(20), BASE.plusMinutes(25));
        when(quizAttemptRepository.findTopByUserIdAndLessonIdOrderByIdDesc(1L, 1L)).thenReturn(Optional.of(attempt));
        when(quizAttemptRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(attempt));
        when(focusLogRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(
                focusLog(BASE.plusMinutes(5), 80.0F),
                focusLog(BASE.plusMinutes(10), 100.0F)
        ));

        FeedbackResponse response = feedbackService.generateFeedback(request());

        assertThat(response.getFocusScore()).isEqualTo(90.0F);
    }

    @Test
    void focusScoreOnlyCountsLogsAfterPreviousAttemptFinished() {
        QuizAttempt previous = attempt(4L, BASE.plusMinutes(10), BASE.plusMinutes(15));
        QuizAttempt current = attempt(5L, BASE.plusMinutes(40), BASE.plusMinutes(45));
        when(quizAttemptRepository.findTopByUserIdAndLessonIdOrderByIdDesc(1L, 1L)).thenReturn(Optional.of(current));
        when(quizAttemptRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(previous, current));
        when(focusLogRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(
                focusLog(BASE.plusMinutes(5), 20.0F),
                focusLog(BASE.plusMinutes(30), 70.0F),
                focusLog(BASE.plusMinutes(50), 10.0F)
        ));

        FeedbackResponse response = feedbackService.generateFeedback(request());

        assertThat(response.getFocusScore()).isEqualTo(70.0F);
    }

    @Test
    void focusScoreFallsBackToEarlierLogsWhenRetakingQuizWithoutRewatching() {
        QuizAttempt previous = attempt(4L, BASE.plusMinutes(10), BASE.plusMinutes(15));
        QuizAttempt current = attempt(5L, BASE.plusMinutes(16), BASE.plusMinutes(20));
        when(quizAttemptRepository.findTopByUserIdAndLessonIdOrderByIdDesc(1L, 1L)).thenReturn(Optional.of(current));
        when(quizAttemptRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(previous, current));
        when(focusLogRepository.findByUserIdAndLessonId(1L, 1L)).thenReturn(List.of(
                focusLog(BASE.plusMinutes(5), 60.0F)
        ));

        FeedbackResponse response = feedbackService.generateFeedback(request());

        assertThat(response.getFocusScore()).isEqualTo(60.0F);
    }

    private FeedbackRequest request() {
        FeedbackRequest request = new FeedbackRequest();
        request.setUserId(1L);
        request.setLessonId(1L);
        return request;
    }

    private QuizAttempt attempt(Long id, LocalDateTime startedAt, LocalDateTime finishedAt) {
        QuizAttempt attempt = new QuizAttempt();
        attempt.setId(id);
        attempt.setUser(user);
        attempt.setLesson(lesson);
        attempt.setScore(100.0F);
        attempt.setStartedAt(startedAt);
        attempt.setFinishedAt(finishedAt);
        return attempt;
    }

    private FocusLog focusLog(LocalDateTime recordedAt, Float focusScore) {
        FocusLog focusLog = new FocusLog();
        focusLog.setUser(user);
        focusLog.setLesson(lesson);
        focusLog.setStatus("focused");
        focusLog.setFocusScore(focusScore);
        focusLog.setRecordedAt(recordedAt);
        return focusLog;
    }
}
