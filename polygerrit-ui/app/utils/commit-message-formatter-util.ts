/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

export interface CommitMessage {
  subject: string;
  body: string[];
  footer: string[];
  hasTrailingBlankLine: boolean;
}

export interface FormattingError {
  type: ErrorType;
  line?: number;
  message: string;
}

export enum ErrorType {
  SUBJECT_TOO_LONG,
  LINE_TOO_LONG,
  MISSING_BLANK_LINE,
  EXTRA_BLANK_LINE,
  INVALID_INDENTATION,
  TRAILING_SPACES,
  COMMENT_LINE,
  LEADING_SPACES,
}

const MAX_SUBJECT_LENGTH = 72;
const MAX_LINE_LENGTH = 72;
const INDENTATION_THRESHOLD = 4;
const BULLET_POINT_REGEX = /^\s*[-+*#]\s/;

function formatCommitMessage(message: CommitMessage): CommitMessage {
  const formattedSubject = formatSubject(message.subject);
  const formattedBody = formatBody(message.body);
  const formattedFooter = formatFooter(message.footer);

  return {
    subject: formattedSubject,
    body: formattedBody,
    footer: formattedFooter,
    hasTrailingBlankLine: message.hasTrailingBlankLine,
  };
}

function formatSubject(subject: string): string {
  return subject.trim();
}

function formatBody(body: string[]): string[] {
  let inCodeBlock = false;
  let paragraphLines: string[] = [];
  const formattedBody: string[] = [];
  let previousWasBulletPoint = false;
  let previousWasEmpty = true; // Track if previous line was empty

  for (const line of body) {
    if (line.trim().startsWith('```')) {
      inCodeBlock = !inCodeBlock;
      formattedBody.push(line.trimEnd());
      previousWasEmpty = false;
      continue;
    }

    if (inCodeBlock || isUntouchedLine(line, previousWasEmpty)) {
      if (!inCodeBlock) {
        previousWasBulletPoint = BULLET_POINT_REGEX.test(line);
      }
      if (paragraphLines.length > 0) {
        formattedBody.push(...splitParagraph(paragraphLines.join(' ')));
      }
      paragraphLines = []; // Reset paragraph
      formattedBody.push(line.trimEnd());
      previousWasEmpty = false;
      continue;
    }

    if (previousWasBulletPoint && line.startsWith('  ')) {
      formattedBody.push(line.trimEnd());
      previousWasEmpty = false;
      continue;
    }

    if (line.trim() === '') {
      if (paragraphLines.length > 0) {
        formattedBody.push(...splitParagraph(paragraphLines.join(' ')));
        paragraphLines = [];
      }
      formattedBody.push('');
      previousWasBulletPoint = false;
      previousWasEmpty = true;
    } else {
      paragraphLines.push(line.trim());
      previousWasEmpty = false;
    }
  }

  if (paragraphLines.length > 0) {
    formattedBody.push(...splitParagraph(paragraphLines.join(' ')));
  }

  return removeConsecutiveBlankLines(formattedBody);
}

function formatFooter(footer: string[]): string[] {
  const formattedFooter = footer.map(line => line.trim());
  return removeConsecutiveBlankLines(formattedFooter);
}
/**
 * Returns true if the line will not be modified by the formatter.
 * For example, quotes, bullet points, and indented lines are untouched.
 */
function isUntouchedLine(line: string, previousWasEmpty: boolean): boolean {
  return (
    line.trimStart().startsWith('> ') ||
    (line.length >= INDENTATION_THRESHOLD &&
      line.substring(0, INDENTATION_THRESHOLD).trim() === '') ||
    BULLET_POINT_REGEX.test(line) ||
    // Check if line is part of a list by looking for any indentation,
    // but only if not first line in paragraph
    (!previousWasEmpty &&
      line.trimStart().length > 0 &&
      line !== line.trimStart())
  );
}

function splitParagraph(paragraph: string): string[] {
  const words = paragraph.split(/\s+/);
  const lines: string[] = [];
  let currentLine = '';

  for (const word of words) {
    if (word.length > MAX_LINE_LENGTH) {
      if (currentLine.length > 0) {
        lines.push(currentLine);
        currentLine = '';
      }
      lines.push(word);
    } else if (
      currentLine.length > 0 &&
      currentLine.length + word.length + 1 > MAX_LINE_LENGTH
    ) {
      lines.push(currentLine);
      currentLine = word;
    } else {
      currentLine += (currentLine ? ' ' : '') + word;
    }
  }

  if (currentLine) {
    lines.push(currentLine);
  }

  return lines;
}

function removeConsecutiveBlankLines(lines: string[]): string[] {
  const filteredLines: string[] = [];
  let previousLineBlank = false;

  for (const line of lines) {
    const isBlank = line.trim() === '';
    if (!isBlank || !previousLineBlank) {
      filteredLines.push(line);
    }
    previousLineBlank = isBlank;
  }

  // Remove leading and trailing blank lines
  while (filteredLines.length > 0 && filteredLines[0].trim() === '') {
    filteredLines.shift();
  }
  while (
    filteredLines.length > 0 &&
    filteredLines[filteredLines.length - 1].trim() === ''
  ) {
    filteredLines.pop();
  }
  return filteredLines;
}

function detectFormattingErrors(
  message: CommitMessage,
  messageString: string
): FormattingError[] {
  const errors: FormattingError[] = [];

  // Check subject
  if (message.subject.length > MAX_SUBJECT_LENGTH) {
    errors.push({
      type: ErrorType.SUBJECT_TOO_LONG,
      line: 1,
      message: `Subject exceeds ${MAX_SUBJECT_LENGTH} characters`,
    });
  }

  if (message.subject.startsWith(' ')) {
    errors.push({
      type: ErrorType.LEADING_SPACES,
      line: 1,
      message: 'Subject should not start with spaces',
    });
  }

  if (message.subject.endsWith(' ')) {
    errors.push({
      type: ErrorType.TRAILING_SPACES,
      line: 1,
      message: 'Subject should not end with spaces',
    });
  }

  const lines = messageString.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim().startsWith('#')) {
      errors.push({
        type: ErrorType.COMMENT_LINE,
        line: i + 1, // Line numbers are 1-based
        message:
          "'#' at line start is a comment marker in Git. Line will be ignored",
      });
    }
  }

  // Check for extra blank lines using the raw messageString
  for (let i = 0; i < lines.length; i++) {
    if (i > 0 && lines[i].trim() === '' && lines[i - 1].trim() === '') {
      const isBetweenSubjectAndBody = i === 1 && message.body.length > 0;
      const isBetweenBodyAndFooter =
        i === message.body.length + (message.subject ? 1 : 0) &&
        message.footer.length > 0;
      const isAtEndOfFooter =
        i === lines.length - 1 && message.footer.length > 0;

      if (
        !isBetweenSubjectAndBody &&
        !isBetweenBodyAndFooter &&
        !isAtEndOfFooter
      ) {
        errors.push({
          type: ErrorType.EXTRA_BLANK_LINE,
          line: i + 1, // Line numbers are 1-based
          message: 'Consecutive blank lines are not allowed',
        });
      }
    }
  }

  // Check body
  let lineNumber = 3;
  let inCodeBlock = false;

  for (const line of message.body) {
    if (line.trim().startsWith('```')) {
      inCodeBlock = !inCodeBlock;
    }

    if (
      !inCodeBlock &&
      !isUntouchedLine(line, false) &&
      line.length > MAX_LINE_LENGTH &&
      !line.includes('://') // Don't flag long URLs
    ) {
      errors.push({
        type: ErrorType.LINE_TOO_LONG,
        line: lineNumber,
        message: `Line exceeds ${MAX_LINE_LENGTH} characters`,
      });
    }
    if (line.endsWith(' ')) {
      errors.push({
        type: ErrorType.TRAILING_SPACES,
        line: lineNumber,
        message: 'Line should not end with spaces',
      });
    }
    lineNumber++;
  }

  // Check footer
  lineNumber = message.body.length + 4;
  for (const line of message.footer) {
    if (line.trim().startsWith('```')) {
      inCodeBlock = !inCodeBlock;
    }

    if (
      !inCodeBlock &&
      !isUntouchedLine(line, false) &&
      line.length > MAX_LINE_LENGTH &&
      !line.includes('://') // Don't flag long URLs
    ) {
      errors.push({
        type: ErrorType.LINE_TOO_LONG,
        line: lineNumber,
        message: `Line exceeds ${MAX_LINE_LENGTH} characters`,
      });
    }
    if (line.endsWith(' ')) {
      errors.push({
        type: ErrorType.TRAILING_SPACES,
        line: lineNumber,
        message: 'Line should not end with spaces',
      });
    }
    if (line.startsWith(' ')) {
      errors.push({
        type: ErrorType.LEADING_SPACES,
        line: lineNumber,
        message: 'Line should not start with spaces',
      });
    }
    lineNumber++;
  }

  return errors;
}

export function parseCommitMessageString(messageString: string): CommitMessage {
  const lines = messageString.split('\n');
  // Remove leading blank lines
  while (lines.length > 0 && lines[0].trim() === '') {
    lines.shift();
  }

  let subject = '';
  let body: string[] = [];
  let footer: string[] = [];
  let hasTrailingBlankLine = false;

  if (lines.length === 0) {
    return {subject, body, footer, hasTrailingBlankLine}; // Handle empty input
  }

  if (lines.length === 1) {
    subject = lines[0];
    return {subject, body, footer, hasTrailingBlankLine}; // Single line case
  }

  subject = lines[0]; // Subject is always the first line
  hasTrailingBlankLine =
    lines.length > 0 && lines[lines.length - 1].trim() === '';

  const footerStartIndex = findStartOfParagraph(
    lines,
    hasTrailingBlankLine ? lines.length - 2 : lines.length - 1
  );

  footer = lines.slice(footerStartIndex, lines.length);
  if (hasTrailingBlankLine) {
    footer.pop();
  }

  // Extract body lines, removing all leading/trailing blank lines
  body = lines.slice(
    firstNonEmptyLineIndex(lines, 2, /* direction */ 1),
    firstNonEmptyLineIndex(lines, footerStartIndex - 1, /* direction */ -1) + 1
  );

  // Check if footer contains any lines in the format "key: value"
  // If not, move footer lines to body and make footer empty
  // This is typically the case when creating a new commit message and the footer is not yet formatted
  const hasFormattedFooterLine = footer.some(line =>
    /^[^:]+:.+/.test(line.trim())
  );
  if (!hasFormattedFooterLine && footer.length > 0) {
    // If body is not empty, add a blank line before appending footer
    if (body.length > 0) {
      body.push('');
    }
    body = body.concat(footer);
    footer = [];
  }

  return {subject, body, footer, hasTrailingBlankLine};
}

function findStartOfParagraph(
  lines: string[],
  lastLineInParagraph: number
): number {
  for (let i = lastLineInParagraph; i >= 0; i--) {
    if (lines[i].trim() === '') {
      return i + 1;
    }
  }
  // on line 0 is subject
  return 1;
}

/**
 * Returns the index of the first non-empty line in the given direction.
 *
 * @param lines The lines of the commit message.
 * @param index The starting index.
 * @param direction The direction to search in (1 for forward, -1 for backward).
 * @return The index of the first non-empty line, or the index of the last line if no non-empty lines are found.
 */
function firstNonEmptyLineIndex(
  lines: string[],
  index: number,
  direction: number
): number {
  while (index >= 0 && index < lines.length && lines[index].trim() === '') {
    index += direction;
  }
  return index;
}

function formatCommitMessageToString(message: CommitMessage): string {
  let result = message.subject;
  if (message.body.length > 0) {
    result += '\n\n' + message.body.join('\n');
  }
  if (message.footer.length > 0) {
    result += '\n\n' + message.footer.join('\n');
  }
  if (message.hasTrailingBlankLine) {
    result += '\n';
  }
  return result;
}

export function formatCommitMessageString(messageString: string): string {
  const commitMessage = parseCommitMessageString(messageString);
  const formattedMessage = formatCommitMessage(commitMessage);
  return formatCommitMessageToString(formattedMessage);
}

export function detectFormattingErrorsInString(
  messageString: string
): FormattingError[] {
  const commitMessage = parseCommitMessageString(messageString);
  return detectFormattingErrors(commitMessage, messageString);
}

export const TEST_ONLY = {parseCommitMessageString};
