/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.inputs.transports.netty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.codec.frame.Delimiters;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.handler.codec.frame.LineBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;

/**
 * A decoder that splits the received {@link ChannelBuffer}s by one or more
 * delimiters.  It is particularly useful for decoding the frames which ends
 * with a delimiter such as {@link Delimiters#nulDelimiter() NUL} or
 * {@linkplain Delimiters#lineDelimiter() newline characters}.
 *
 * <h3>Predefined delimiters</h3>
 * <p>
 * {@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 * <p>
 * {@link LenientDelimiterBasedFrameDecoder} allows you to specify more than one
 * delimiter.  If more than one delimiter is found in the buffer, it chooses
 * the delimiter which produces the shortest frame.  For example, if you have
 * the following data in the buffer:
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 * a {@link LenientDelimiterBasedFrameDecoder}{@code (}{@link Delimiters#lineDelimiter() Delimiters.lineDelimiter()}{@code )}
 * will choose {@code '\n'} as the first delimiter and produce two frames:
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 *
 *
 * @apiviz.uses org.jboss.netty.handler.codec.frame.Delimiters - - useful
 */
public class LenientDelimiterBasedFrameDecoder extends FrameDecoder {

    private final ChannelBuffer[] delimiters;
    private final int maxFrameLength;
    private final boolean stripDelimiter;
    private final boolean failFast;
    private final boolean emitLastLineWithoutDelimiter;
    private boolean discardingTooLongFrame;
    private int tooLongFrameLength;
    /** Set only when decoding with "\n" and "\r\n" as the delimiter.  */
    private final LenientLineBasedFrameDecoder lineBasedDecoder;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiter  the delimiter
     */
    public LenientDelimiterBasedFrameDecoder(int maxFrameLength, ChannelBuffer delimiter) {
        this(maxFrameLength, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiter  the delimiter
     */
    public LenientDelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ChannelBuffer delimiter) {
        this(maxFrameLength, stripDelimiter, false, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiter  the delimiter
     */
    public LenientDelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast,
            ChannelBuffer delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, true, delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes()));
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiters  the delimiters
     */
    public LenientDelimiterBasedFrameDecoder(int maxFrameLength, ChannelBuffer... delimiters) {
        this(maxFrameLength, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiters  the delimiters
     */
    public LenientDelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ChannelBuffer... delimiters) {
        this(maxFrameLength, stripDelimiter, false, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param emitLastLineWithoutDelimiter emit the last line even if it doesn't
     *                                     end with the delimiter
     * @param delimiters  the delimiters
     */
    public LenientDelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast, boolean emitLastLineWithoutDelimiter, ChannelBuffer... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }

        if (isLineBased(delimiters) && !isSubclass()) {
            lineBasedDecoder = new LenientLineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast, emitLastLineWithoutDelimiter);
            this.delimiters = null;
        } else {
            this.delimiters = new ChannelBuffer[delimiters.length];
            for (int i = 0; i < delimiters.length; i ++) {
                ChannelBuffer d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
        this.emitLastLineWithoutDelimiter = emitLastLineWithoutDelimiter;
    }

    /** Returns true if the delimiters are "\n" and "\r\n".  */
    private static boolean isLineBased(final ChannelBuffer[] delimiters) {
        if (delimiters.length != 2) {
            return false;
        }
        ChannelBuffer a = delimiters[0];
        ChannelBuffer b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        return a.capacity() == 2 && b.capacity() == 1
            && a.getByte(0) == '\r' && a.getByte(1) == '\n'
            && b.getByte(0) == '\n';
    }

    /**
     * Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder
     */
    private boolean isSubclass() {
        return getClass() != LenientDelimiterBasedFrameDecoder.class;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer) throws Exception {
        if (lineBasedDecoder != null) {
            return lineBasedDecoder.decode(ctx, channel, buffer);
        }
        // Try all delimiters and choose the delimiter which yields the shortest frame.
        int minFrameLength = Integer.MAX_VALUE;
        ChannelBuffer minDelim = null;
        for (ChannelBuffer delim: delimiters) {
            int frameLength = indexOf(buffer, delim);
            if (frameLength >= 0 && frameLength < minFrameLength) {
                minFrameLength = frameLength;
                minDelim = delim;
            }
        }

        if (minDelim != null) {
            int minDelimLength = minDelim.capacity();
            ChannelBuffer frame;

            if (discardingTooLongFrame) {
                // We've just finished discarding a very large frame.
                // Go back to the initial state.
                discardingTooLongFrame = false;
                buffer.skipBytes(minFrameLength + minDelimLength);

                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;
                if (!failFast) {
                    fail(ctx, tooLongFrameLength);
                }
                return null;
            }

            if (minFrameLength > maxFrameLength) {
                // Discard read frame.
                buffer.skipBytes(minFrameLength + minDelimLength);
                fail(ctx, minFrameLength);
                return null;
            }

            if (stripDelimiter) {
                frame = extractFrame(buffer, buffer.readerIndex(), minFrameLength);
            } else {
                frame = extractFrame(buffer, buffer.readerIndex(), minFrameLength + minDelimLength);
            }
            buffer.skipBytes(minFrameLength + minDelimLength);

            return frame;
        } else if (emitLastLineWithoutDelimiter && !channel.isConnected()) {
            minFrameLength = buffer.readableBytes();
            ChannelBuffer frame;

            if (discardingTooLongFrame) {
                // We've just finished discarding a very large frame.
                // Go back to the initial state.
                discardingTooLongFrame = false;
                buffer.skipBytes(minFrameLength);

                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;
                if (!failFast) {
                    fail(ctx, tooLongFrameLength);
                }
                return null;
            }

            if (minFrameLength > maxFrameLength) {
                // Discard read frame.
                buffer.skipBytes(minFrameLength);
                fail(ctx, minFrameLength);
                return null;
            }

            frame = extractFrame(buffer, buffer.readerIndex(), minFrameLength);
            buffer.skipBytes(minFrameLength);

            return frame;
        } else {
            if (!discardingTooLongFrame) {
                if (buffer.readableBytes() > maxFrameLength) {
                    // Discard the content of the buffer until a delimiter is found.
                    tooLongFrameLength = buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                    discardingTooLongFrame = true;
                    if (failFast) {
                        fail(ctx, tooLongFrameLength);
                    }
                }
            } else {
                // Still discarding the buffer since a delimiter is not found.
                tooLongFrameLength += buffer.readableBytes();
                buffer.skipBytes(buffer.readableBytes());
            }
            return null;
        }
    }

    private void fail(ChannelHandlerContext ctx, long frameLength) {
        if (frameLength > 0) {
            Channels.fireExceptionCaught(
                    ctx.getChannel(),
                    new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded"));
        } else {
            Channels.fireExceptionCaught(
                    ctx.getChannel(),
                    new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            " - discarding"));
        }
    }

    /**
     * Returns the number of bytes between the readerIndex of the haystack and
     * the first needle found in the haystack.  -1 is returned if no needle is
     * found in the haystack.
     */
    private static int indexOf(ChannelBuffer haystack, ChannelBuffer needle) {
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i ++) {
            int haystackIndex = i;
            int needleIndex;
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex ++) {
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    haystackIndex ++;
                    if (haystackIndex == haystack.writerIndex() &&
                        needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        return -1;
    }

    private static void validateDelimiter(ChannelBuffer delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.readable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException(
                    "maxFrameLength must be a positive integer: " +
                    maxFrameLength);
        }
    }
}
