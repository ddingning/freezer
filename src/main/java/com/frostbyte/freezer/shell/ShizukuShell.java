package com.frostbyte.freezer.shell;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public final class ShizukuShell {
    private ShizukuShell() {
    }

    public static ShellResult run(String command, long timeoutMs) {
        try {
            if (!Shizuku.pingBinder()) {
                return ShellResult.notStarted("Shizuku 服务未运行");
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShellResult.notStarted("Shizuku 未授权");
            }
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                return ShellResult.notStarted("Shizuku Binder 为空");
            }
            IShizukuService service = IShizukuService.Stub.asInterface(
                    new ShizukuBinderWrapper(binder));
            IRemoteProcess remote = service.newProcess(
                    new String[]{"/system/bin/sh", "-c", command}, null, null);
            if (remote == null) {
                return ShellResult.notStarted("Shizuku newProcess 返回空");
            }
            return collect(remote, timeoutMs);
        } catch (Throwable e) {
            return ShellResult.notStarted(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static ShellResult collect(final IRemoteProcess remote, long timeoutMs) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ParcelFileDescriptor outPfd = null;
        ParcelFileDescriptor errPfd = null;
        ParcelFileDescriptor inPfd = null;
        try {
            outPfd = remote.getInputStream();
            errPfd = remote.getErrorStream();
            inPfd = remote.getOutputStream();
            InputStream out = new ParcelFileDescriptor.AutoCloseInputStream(outPfd);
            InputStream err = new ParcelFileDescriptor.AutoCloseInputStream(errPfd);
            if (inPfd != null) {
                new ParcelFileDescriptor.AutoCloseOutputStream(inPfd).close();
                inPfd = null;
            }
            Thread outThread = copyThread(out, stdout);
            Thread errThread = copyThread(err, stderr);
            outThread.start();
            errThread.start();

            final AtomicBoolean finished = new AtomicBoolean(false);
            final AtomicBoolean timedOut = new AtomicBoolean(false);
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(timeoutMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!finished.get()) {
                        timedOut.set(true);
                        try {
                            remote.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "shizuku-shell-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            int exitCode = -1;
            try {
                exitCode = remote.waitFor();
            } catch (Throwable e) {
                if (!timedOut.get()) {
                    return new ShellResult(true, false, -1, "", "",
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                finished.set(true);
                watchdog.interrupt();
            }
            join(outThread);
            join(errThread);
            return new ShellResult(true, timedOut.get(), exitCode,
                    new String(stdout.toByteArray()), new String(stderr.toByteArray()),
                    timedOut.get() ? "timeout" : null);
        } catch (Throwable e) {
            return new ShellResult(true, false, -1, "", "",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                if (outPfd != null) {
                    outPfd.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (errPfd != null) {
                    errPfd.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (inPfd != null) {
                    inPfd.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static Thread copyThread(final InputStream input, final ByteArrayOutputStream output) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[4096];
                int read;
                try (InputStream in = input) {
                    while ((read = in.read(chunk)) != -1) {
                        output.write(chunk, 0, read);
                    }
                } catch (IOException ignored) {
                }
            }
        }, "shizuku-shell-reader");
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final ShellExecutor EXECUTOR = new ShellExecutor() {
        @Override
        public ShellResult run(String command, long timeoutMs) {
            return ShizukuShell.run(command, timeoutMs);
        }
    };
}
