package com.datn.backend.service;

import com.datn.backend.constants.AppConstants;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final LessonRepository lessonRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final FocusLogRepository focusLogRepository;

    public FeedbackService(
            FeedbackRepository feedbackRepository,
            UserRepository userRepository,
            LessonRepository lessonRepository,
            QuizAttemptRepository quizAttemptRepository,
            FocusLogRepository focusLogRepository
    ) {
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.lessonRepository = lessonRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.focusLogRepository = focusLogRepository;
    }

    @Transactional
    public FeedbackResponse generateFeedback(FeedbackRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Lesson lesson = lessonRepository.findById(request.getLessonId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found"));
        QuizAttempt quizAttempt = quizAttemptRepository
                .findTopByUserIdAndLessonIdOrderByIdDesc(user.getId(), lesson.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz attempt not found"));

        Float quizScore = quizAttempt.getScore() == null ? 0.0F : quizAttempt.getScore();
        Float focusScore = calculateSessionFocusScore(quizAttempt);
        String recommendation = buildRecommendation(quizScore, focusScore);

        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setLesson(lesson);
        feedback.setQuizAttempt(quizAttempt);
        feedback.setQuizScore(quizScore);
        feedback.setFocusScore(focusScore);
        feedback.setRecommendation(recommendation);
        feedback.setMessage(buildMessage(recommendation, quizScore, focusScore));

        return FeedbackResponse.from(feedbackRepository.save(feedback));
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> getFeedbacksByUserId(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        return feedbackRepository.findByUserId(userId)
                .stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> getFeedbacksByLessonId(Long lessonId) {
        if (!lessonRepository.existsById(lessonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found");
        }

        return feedbackRepository.findByLessonId(lessonId)
                .stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> getFeedbacksByUserIdAndLessonId(Long userId, Long lessonId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        if (!lessonRepository.existsById(lessonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found");
        }

        return feedbackRepository.findByUserIdAndLessonId(userId, lessonId)
                .stream()
                .map(FeedbackResponse::from)
                .toList();
    }

    /**
     * Focus logs được ghi khi xem video bài học, tức là TRƯỚC khi bắt đầu quiz.
     * Phiên học của một lần quiz = từ lúc kết thúc lần quiz trước (cùng user/lesson) đến lúc nộp quiz này.
     * Nếu phiên đó không có log (vd làm lại quiz ngay mà không xem lại video) thì dùng toàn bộ log trước đó.
     */
    private Float calculateSessionFocusScore(QuizAttempt attempt) {
        Long userId = attempt.getUser().getId();
        Long lessonId = attempt.getLesson().getId();
        LocalDateTime sessionEnd = attempt.getFinishedAt() != null ? attempt.getFinishedAt() : LocalDateTime.now();
        LocalDateTime sessionStart = quizAttemptRepository.findByUserIdAndLessonId(userId, lessonId)
                .stream()
                .filter(previous -> previous.getId() < attempt.getId())
                .map(QuizAttempt::getFinishedAt)
                .filter(finishedAt -> finishedAt != null && finishedAt.isBefore(sessionEnd))
                .max(LocalDateTime::compareTo)
                .orElse(null);

        List<FocusLog> logsBeforeEnd = focusLogRepository.findByUserIdAndLessonId(userId, lessonId)
                .stream()
                .filter(log -> log.getRecordedAt() != null && !log.getRecordedAt().isAfter(sessionEnd))
                .toList();
        List<FocusLog> sessionLogs = sessionStart == null
                ? logsBeforeEnd
                : logsBeforeEnd.stream().filter(log -> log.getRecordedAt().isAfter(sessionStart)).toList();

        return averageFocusScore(sessionLogs.isEmpty() ? logsBeforeEnd : sessionLogs);
    }

    private Float averageFocusScore(List<FocusLog> focusLogs) {
        double average = focusLogs.stream()
                .map(FocusLog::getFocusScore)
                .filter(score -> score != null)
                .mapToDouble(Float::doubleValue)
                .average()
                .orElse(0.0);
        return (float) Math.round(average);
    }

    private String buildRecommendation(Float quizScore, Float focusScore) {
        if (quizScore >= AppConstants.QuizResult.PASS_SCORE && focusScore >= AppConstants.QuizResult.PASS_SCORE) {
            return AppConstants.Recommendation.NEXT_LESSON;
        }
        if (quizScore < AppConstants.QuizResult.PASS_SCORE) {
            return AppConstants.Recommendation.REVIEW_LESSON;
        }
        return AppConstants.Recommendation.PRACTICE_MORE;
    }

    private String buildMessage(String recommendation, Float quizScore, Float focusScore) {
        boolean quizLow = quizScore < AppConstants.QuizResult.PASS_SCORE;
        boolean focusLow = focusScore < AppConstants.QuizResult.PASS_SCORE;

        if (AppConstants.Recommendation.NEXT_LESSON.equals(recommendation)) {
            return String.format(
                    "Bạn đã hoàn thành tốt bài học với điểm quiz %.0f%% và mức độ tập trung %.0f%%. Hãy chuyển sang bài học tiếp theo.",
                    quizScore, focusScore
            );
        }
        if (AppConstants.Recommendation.REVIEW_LESSON.equals(recommendation)) {
            if (focusLow && focusScore > 0) {
                return String.format(
                        "Điểm quiz của bạn là %.0f%% (chưa đạt) và mức độ tập trung %.0f%% còn thấp. Hãy học lại bài, duy trì sự tập trung và làm lại quiz.",
                        quizScore, focusScore
                );
            }
            return String.format(
                    "Điểm quiz của bạn là %.0f%%, chưa đạt mức yêu cầu. Bạn nên học lại bài này và làm lại quiz để nắm chắc kiến thức hơn.",
                    quizScore
            );
        }
        return String.format(
                "Điểm quiz %.0f%% đạt yêu cầu nhưng mức độ tập trung %.0f%% còn thấp. Hãy luyện tập thêm và giữ môi trường học ít xao nhãng hơn.",
                quizScore, focusScore
        );
    }
}
