package com.datn.backend.service;

import com.datn.backend.entity.LearningProgress;
import com.datn.backend.entity.Lesson;
import com.datn.backend.entity.QuizAttempt;
import com.datn.backend.entity.User;
import com.datn.backend.repository.CourseRepository;
import com.datn.backend.repository.LearningProgressRepository;
import com.datn.backend.repository.LessonRepository;
import com.datn.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LearningProgressServiceTest {

    @Mock
    private LearningProgressRepository learningProgressRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private CourseRepository courseRepository;

    private LearningProgressService learningProgressService;

    @BeforeEach
    void setUp() {
        learningProgressService = new LearningProgressService(
                learningProgressRepository,
                userRepository,
                lessonRepository,
                courseRepository
        );

        lenient().when(learningProgressRepository.findByUserIdAndLessonIdOrderByUpdatedAtDesc(1L, 1L))
                .thenReturn(List.of());
        lenient().when(learningProgressRepository.save(any(LearningProgress.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void highScoreIncreasesUserLevelAndUpdatesProgress() {
        User user = user("basic");

        learningProgressService.updateAfterQuizSubmit(attempt(user, 85.0F));

        assertThat(user.getCurrentLevel()).isEqualTo("medium");
        verify(userRepository).save(user);
        verify(learningProgressRepository).save(any(LearningProgress.class));
    }

    @Test
    void lowScoreDecreasesUserLevel() {
        User user = user("hard");

        learningProgressService.updateAfterQuizSubmit(attempt(user, 40.0F));

        assertThat(user.getCurrentLevel()).isEqualTo("medium");
        verify(userRepository).save(user);
    }

    @Test
    void middleScoreKeepsUserLevel() {
        User user = user("medium");

        learningProgressService.updateAfterQuizSubmit(attempt(user, 60.0F));

        assertThat(user.getCurrentLevel()).isEqualTo("medium");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void calculateNewLevelStaysWithinBounds() {
        assertThat(LearningProgressService.calculateNewLevel("hard", 100.0F)).isEqualTo("hard");
        assertThat(LearningProgressService.calculateNewLevel("basic", 0.0F)).isEqualTo("basic");
        assertThat(LearningProgressService.calculateNewLevel("medium", 80.0F)).isEqualTo("hard");
        assertThat(LearningProgressService.calculateNewLevel("medium", 50.0F)).isEqualTo("medium");
        assertThat(LearningProgressService.calculateNewLevel("medium", 49.9F)).isEqualTo("basic");
        assertThat(LearningProgressService.calculateNewLevel(null, null)).isEqualTo("basic");
    }

    private User user(String level) {
        User user = new User();
        user.setId(1L);
        user.setCurrentLevel(level);
        return user;
    }

    private QuizAttempt attempt(User user, Float score) {
        Lesson lesson = new Lesson();
        lesson.setId(1L);

        QuizAttempt attempt = new QuizAttempt();
        attempt.setId(1L);
        attempt.setUser(user);
        attempt.setLesson(lesson);
        attempt.setScore(score);
        return attempt;
    }
}
