package qupath.ext.mantis.mrxsconvert.runner;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough OME-TIFF reading to compare image and channel metadata with {@code reference/outputs}.
 * The full pixel comparison is spec 09's harness.
 */
final class OmeTiffMetadata {

	private static final int IMAGE_DESCRIPTION = 270;
	private static final Pattern ELEMENT = Pattern.compile("<(?:\\w+:)?(Image|Pixels|Channel)\\b([^>]*)>");
	private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)=\"([^\"]*)\"");
	/**
	 * Differ between runs or tool versions. {@code reference/outputs} (Bio-Formats 7.3.0) say BigEndian="true", the
	 * pinned tools write "false"; for 8-bit pixels the byte order changes nothing.
	 */
	private static final List<String> IGNORED = List.of("ID", "BigEndian");

	private OmeTiffMetadata() {}

	/**
	 * Image, Pixels and Channel elements in document order, each as "Element{attr=value, …}" without IDs.
	 */
	static List<String> summary(Path tiff) throws IOException {
		String xml = imageDescription(tiff);
		List<String> summary = new ArrayList<>();
		Matcher element = ELEMENT.matcher(xml);
		while (element.find()) {
			String name = element.group(1);
			Map<String, String> attributes = new TreeMap<>();
			Matcher attribute = ATTRIBUTE.matcher(element.group(2));
			while (attribute.find()) {
				if (!IGNORED.contains(attribute.group(1)))
					attributes.put(attribute.group(1), attribute.group(2));
			}
			summary.add(name + attributes);
		}
		return summary;
	}

	/** The first IFD's ImageDescription, where OME-TIFF keeps its OME-XML. Classic TIFF and BigTIFF. */
	static String imageDescription(Path tiff) throws IOException {
		try (FileChannel channel = FileChannel.open(tiff)) {
			ByteBuffer header = read(channel, 0, 16, ByteOrder.LITTLE_ENDIAN);
			ByteOrder order = header.getShort(0) == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
			header.order(order);
			boolean big = (header.getShort(2) & 0xffff) == 43;
			long ifd = big ? header.getLong(8) : header.getInt(4) & 0xffffffffL;
			long count = big ? read(channel, ifd, 8, order).getLong(0) : read(channel, ifd, 2, order).getShort(0) & 0xffff;
			int entrySize = big ? 20 : 12;
			ByteBuffer entries = read(channel, ifd + (big ? 8 : 2), (int) (count * entrySize), order);
			for (int i = 0; i < count; i++) {
				int at = i * entrySize;
				if ((entries.getShort(at) & 0xffff) != IMAGE_DESCRIPTION)
					continue;
				long length = big ? entries.getLong(at + 4) : entries.getInt(at + 4) & 0xffffffffL;
				long offset = big ? entries.getLong(at + 12) : entries.getInt(at + 8) & 0xffffffffL;
				ByteBuffer text = read(channel, offset, (int) length, order);
				return new String(text.array(), StandardCharsets.UTF_8).replace("\0", "");
			}
			throw new IOException("No ImageDescription in " + tiff);
		}
	}

	private static ByteBuffer read(FileChannel channel, long position, int length, ByteOrder order) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(length).order(order);
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, position + buffer.position()) < 0)
				throw new EOFException();
		}
		return buffer.flip();
	}
}
