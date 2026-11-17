package com.erlan.testtask.logging;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

final class BoundedResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final int limit;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    BoundedResponseWrapper(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (outputStream == null) {
            ServletOutputStream delegate = super.getOutputStream();
            outputStream = new ServletOutputStream() {
                @Override
                public void write(int value) throws IOException {
                    delegate.write(value);
                    if (captured.size() < limit) {
                        captured.write(value);
                    }
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    delegate.write(bytes, offset, length);
                    int available = limit - captured.size();
                    if (available > 0) {
                        captured.write(bytes, offset, Math.min(length, available));
                    }
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    delegate.setWriteListener(listener);
                }

                @Override
                public void flush() throws IOException {
                    delegate.flush();
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
        }
        return writer;
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        captured.reset();
    }

    @Override
    public void reset() {
        super.reset();
        captured.reset();
    }

    @Override
    public void sendError(int status) throws IOException {
        captured.reset();
        super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        captured.reset();
        super.sendError(status, message);
    }

    void flushCapture() throws IOException {
        if (writer != null) {
            writer.flush();
        } else if (outputStream != null) {
            outputStream.flush();
        }
    }

    byte[] getCapturedContent() {
        return captured.toByteArray();
    }
}
