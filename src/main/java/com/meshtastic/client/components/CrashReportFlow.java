package com.meshtastic.client.components;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.logging.SessionCrashLogManager;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.platform.OsDetect;
import com.meshtastic.client.service.CrashReportService;
import com.meshtastic.client.tray.MacOsNativeTrayService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class CrashReportFlow {

    private static final Logger log = LoggerFactory.getLogger(CrashReportFlow.class);
    private static final Duration STARTUP_PROMPT_SETTLE_DELAY = Duration.millis(180);
    private static final Duration STARTUP_PROMPT_FOCUS_TIMEOUT = Duration.seconds(1);

    private CrashReportFlow() {}

    public static void showPendingCrashPrompt(Window owner, Path crashLog, Runnable onFinished) {
        showStartupPromptWhenWindowReady(owner, () ->
                CrashReportPrompt.show(owner, CrashReportPrompt.Content.startupCrash(), decision -> {
                    if (!decision.sendReport()) {
                        SessionCrashLogManager.deletePendingCrashLog(crashLog);
                        runHook(onFinished);
                        return;
                    }

                    submitReport(
                            owner,
                            crashLog,
                            decision.comment(),
                            false,
                            new SubmissionHooks(
                                    null,
                                    () -> SessionCrashLogManager.deletePendingCrashLog(crashLog),
                                    null,
                                    onFinished,
                                    decision.email(),
                                    "Лог отправлен разработчикам: issue #"
                            )
                    );
                })
        );
    }

    public static void showProblemReport(Window owner) {
        CrashReportPrompt.show(owner, CrashReportPrompt.Content.problemReport(), decision -> {
            if (!decision.sendReport()) {
                return;
            }

            Path snapshot;
            try {
                snapshot = SessionCrashLogManager.createReportLogSnapshot();
            } catch (IOException e) {
                log.error("Failed to create session log snapshot for problem report", e);
                ModalPane.showError(
                        "Не удалось подготовить отчёт",
                        "Не получилось собрать технический лог текущей сессии.\n\n" + humanizeError(e)
                );
                return;
            }

            submitReport(
                    owner,
                    snapshot,
                    decision.comment(),
                    true,
                    new SubmissionHooks(
                            () -> deleteReportSnapshot(snapshot),
                            () -> deleteReportSnapshot(snapshot),
                            () -> deleteReportSnapshot(snapshot),
                            null,
                            decision.email(),
                            "Отчёт отправлен разработчикам: issue #"
                    )
            );
        });
    }

    private static void submitReport(Window owner,
                                     Path logFile,
                                     String comment,
                                     boolean problemReport,
                                     SubmissionHooks hooks) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CrashReportPrompt.ProgressDialog progressDialog = CrashReportPrompt.showProgress(owner);
        progressDialog.setOnCancel(() -> {
            cancelled.set(true);
            runHook(hooks.onCancel());
            Thread worker = workerRef.get();
            if (worker != null) {
                worker.interrupt();
            }
            runHook(hooks.onFinished());
        });

        Thread worker = Thread.ofVirtual().name("meshapp-report-upload").unstarted(() -> {
            try {
                CrashReportService crashReportService = CrashReportService.createDefault();
                CrashReportService.SubmissionResult result = problemReport
                        ? crashReportService.submitProblemReport(logFile, comment, hooks.contactEmail(), buildCrashContext())
                        : crashReportService.submitCrashReport(logFile, comment, hooks.contactEmail(), buildCrashContext());

                if (cancelled.get()) {
                    return;
                }

                runHook(hooks.onSuccess());
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        return;
                    }
                    progressDialog.close(() -> {
                        Toast.show(Toast.Type.SUCCESS, hooks.successMessagePrefix() + result.issueIndex());
                        runHook(hooks.onFinished());
                    });
                });
            } catch (Exception e) {
                if (cancelled.get() || e instanceof InterruptedException) {
                    return;
                }

                log.error("Failed to submit report", e);
                runHook(hooks.onFailure());
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        return;
                    }
                    progressDialog.close(() -> {
                        ModalPane.showError(
                                problemReport ? "Не удалось отправить отчёт" : "Не удалось отправить лог",
                                failureMessage(problemReport, e)
                        );
                        ModalPane pane = ModalPane.getInstance();
                        if (pane != null) {
                            pane.setOnHidden(() -> runHook(hooks.onFinished()));
                        } else {
                            runHook(hooks.onFinished());
                        }
                    });
                });
            }
        });

        workerRef.set(worker);
        if (!cancelled.get()) {
            worker.start();
        }
    }

    private static CrashReportService.CrashContext buildCrashContext() {
        return new CrashReportService.CrashContext(
                MeshApp.APPLICATION_VERSION,
                MeshApp.VERSION_CODE,
                systemProperty("os.name"),
                systemProperty("os.version"),
                systemProperty("os.arch")
        );
    }

    private static String failureMessage(boolean problemReport, Exception exception) {
        String prefix = problemReport
                ? "Не удалось отправить отчёт о проблеме."
                : "Сохранённый лог останется на диске и будет снова предложен при следующем запуске.";
        return prefix + "\n\n" + humanizeError(exception);
    }

    private static String humanizeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Неизвестная ошибка отправки.";
        }
        return message;
    }

    private static String systemProperty(String key) {
        return System.getProperty(key, "unknown").trim();
    }

    private static void deleteReportSnapshot(Path snapshot) {
        if (snapshot == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(snapshot)) {
            for (Path current : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        } catch (Exception e) {
            log.warn("Failed to delete temporary report snapshot {}", snapshot, e);
        }
    }

    private static void showStartupPromptWhenWindowReady(Window owner, Runnable action) {
        if (owner == null) {
            action.run();
            return;
        }

        PauseTransition settleDelay = new PauseTransition(STARTUP_PROMPT_SETTLE_DELAY);
        PauseTransition fallbackDelay = new PauseTransition(STARTUP_PROMPT_FOCUS_TIMEOUT);
        AtomicBoolean shown = new AtomicBoolean(false);

        Runnable showOnce = () -> {
            if (!shown.compareAndSet(false, true)) {
                return;
            }
            fallbackDelay.stop();
            settleDelay.stop();
            Platform.runLater(action);
        };

        Runnable openAfterSettle = () -> {
            settleDelay.stop();
            settleDelay.setOnFinished(event -> showOnce.run());
            settleDelay.playFromStart();
        };

        ChangeListener<Boolean> focusListener = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable,
                                Boolean oldValue,
                                Boolean newValue) {
                if (!Boolean.TRUE.equals(newValue)) {
                    return;
                }
                owner.focusedProperty().removeListener(this);
                openAfterSettle.run();
            }
        };

        owner.focusedProperty().addListener(focusListener);
        fallbackDelay.setOnFinished(event -> {
            owner.focusedProperty().removeListener(focusListener);
            openAfterSettle.run();
        });

        requestWindowActivation(owner);
        if (owner.isFocused()) {
            owner.focusedProperty().removeListener(focusListener);
            openAfterSettle.run();
            return;
        }
        fallbackDelay.playFromStart();
    }

    private static void requestWindowActivation(Window owner) {
        if (!(owner instanceof Stage stage)) {
            return;
        }

        if (OsDetect.isMacOs()) {
            MacOsNativeTrayService.activateApplication();
            MacOsNativeTrayService.focusWindow(stage);
            return;
        }

        stage.toFront();
        stage.requestFocus();
    }

    private static void runHook(Runnable hook) {
        if (hook != null) {
            hook.run();
        }
    }

    private record SubmissionHooks(Runnable onCancel,
                                   Runnable onSuccess,
                                   Runnable onFailure,
                                   Runnable onFinished,
                                   String contactEmail,
                                   String successMessagePrefix) {}
}
