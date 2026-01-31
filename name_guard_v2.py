"""
Name Guard V2: 中文人名识别模块（优化版）

核心改进：
1. 简化代码结构，提高可读性
2. 统一的候选词提取和打分流程
3. 贪心算法处理重叠，优先保留高分候选
4. 支持前缀（老X、小X、阿X）和后缀（X哥、X姐）两种模式
5. 缓存重复计算，提升性能
"""
import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Set
from dataclasses import dataclass, field
import yaml


@dataclass
class NameGuardConfig:
    """Name Guard 配置"""
    # 策略
    threshold: int = 100
    name_placeholder_format: str = "<<NAME_{index}>>"
    require_strong_signal: bool = True

    # 规则权重
    honorific_suffix_weight: int = 100
    honorific_prefix_weight: int = 100  # 新增：前缀权重
    call_position_weight: int = 80
    repetition_weight: int = 40
    pos_exclusion_weight: int = -30
    length_structure_weight: int = 10

    # 规则模式
    honorific_suffix_patterns: List[str] = field(default_factory=list)
    honorific_prefix_patterns: List[str] = field(default_factory=lambda: ["老", "小", "阿"])  # 新增
    repetition_min_occurrences: int = 2
    pos_exclusion_words: Set[str] = field(default_factory=set)
    length_preferred: List[int] = field(default_factory=lambda: [1, 2, 3])
    length_exclude_endings: List[str] = field(default_factory=list)

    # 强排除规则
    interjections: Set[str] = field(default_factory=set)
    action_verbs: Set[str] = field(default_factory=set)
    pronouns: Set[str] = field(default_factory=set)
    numbers_quantifiers: Set[str] = field(default_factory=set)
    kinship_roles: Set[str] = field(default_factory=set)

    # 特殊处理
    known_names: Set[str] = field(default_factory=set)
    exclude_names: Set[str] = field(default_factory=set)


def load_config(config_path: Optional[Path] = None) -> NameGuardConfig:
    """加载配置"""
    if config_path is None:
        config_path = Path(__file__).parent / "name_guard.yaml"

    if not config_path.exists():
        return NameGuardConfig()

    with open(config_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    strategy = cfg.get("strategy", {})
    rules = cfg.get("rules", {})
    exclusion = cfg.get("strong_exclusion", {})
    special = cfg.get("special", {})

    honorific = rules.get("honorific_suffix", {})
    call_pos = rules.get("call_position", {})
    repetition = rules.get("repetition", {})
    pos_excl = rules.get("pos_exclusion", {})
    length = rules.get("length_structure", {})

    # 分离前缀和后缀模式
    patterns = honorific.get("patterns", [])
    prefix_patterns = [p for p in patterns if p in ["老", "小", "阿"]]
    suffix_patterns = [p for p in patterns if p not in ["老", "小", "阿"]]

    return NameGuardConfig(
        threshold=strategy.get("threshold", 100),
        name_placeholder_format=strategy.get("name_placeholder_format", "<<NAME_{index}>>"),
        require_strong_signal=strategy.get("require_strong_signal", True),

        honorific_suffix_weight=honorific.get("weight", 100) if honorific.get("enabled", True) else 0,
        honorific_prefix_weight=honorific.get("weight", 100) if honorific.get("enabled", True) else 0,
        call_position_weight=call_pos.get("weight", 80) if call_pos.get("enabled", True) else 0,
        repetition_weight=repetition.get("weight", 40) if repetition.get("enabled", True) else 0,
        pos_exclusion_weight=pos_excl.get("weight", -30) if pos_excl.get("enabled", True) else 0,
        length_structure_weight=length.get("weight", 10) if length.get("enabled", True) else 0,

        honorific_suffix_patterns=suffix_patterns,
        honorific_prefix_patterns=prefix_patterns if prefix_patterns else ["老", "小", "阿"],
        repetition_min_occurrences=repetition.get("min_occurrences", 2),
        pos_exclusion_words=set(pos_excl.get("exclude_words", [])),
        length_preferred=length.get("preferred_lengths", [1, 2, 3]),
        length_exclude_endings=length.get("exclude_endings", []),

        interjections=set(exclusion.get("interjections", [])),
        action_verbs=set(exclusion.get("action_verbs", [])),
        pronouns=set(exclusion.get("pronouns", [])),
        numbers_quantifiers=set(exclusion.get("numbers_quantifiers", [])),
        kinship_roles=set(exclusion.get("kinship_roles", [])),

        known_names=set(special.get("known_names", [])),
        exclude_names=set(special.get("exclude_names", [])),
    )


@dataclass
class NameCandidate:
    """人名候选"""
    name: str           # 人名（不含前后缀）
    start: int          # 在part中的起始位置（人名部分）
    end: int            # 在part中的结束位置（人名部分）
    score: int          # 打分
    has_affix: bool     # 是否带前缀或后缀
    affix_type: str = ""  # "prefix" 或 "suffix" 或 ""
    affix: str = ""     # 前缀或后缀内容

    @property
    def priority(self) -> Tuple[int, int, int]:
        """排序优先级：(是否带前后缀, 分数, 负位置)"""
        return (1 if self.has_affix else 0, self.score, -self.start)


class NameGuard:
    """人名识别器（优化版）"""

    # 边界字符集
    BOUNDARY_CHARS = set('，。！？、；：,.!?;: \t\n')

    # 中文字符正则
    CHINESE_PATTERN = re.compile(r'[\u4e00-\u9fff]+')

    def __init__(self, config: Optional[NameGuardConfig] = None):
        self.config = config or load_config()
        self._build_patterns()

    def _build_patterns(self):
        """预编译正则模式"""
        # 后缀模式（按长度降序，优先匹配长后缀）
        sorted_suffixes = sorted(self.config.honorific_suffix_patterns, key=len, reverse=True)
        self._suffix_pattern = re.compile(
            r'^([\u4e00-\u9fff]{1,3})(' + '|'.join(re.escape(s) for s in sorted_suffixes) + r')$'
        ) if sorted_suffixes else None

        # 前缀模式（老X、小X、阿X）
        sorted_prefixes = sorted(self.config.honorific_prefix_patterns, key=len, reverse=True)
        self._prefix_pattern = re.compile(
            r'^(' + '|'.join(re.escape(p) for p in sorted_prefixes) + r')([\u4e00-\u9fff]{1,2})$'
        ) if sorted_prefixes else None

    def is_strongly_excluded(self, word: str) -> bool:
        """闸门1：强排除检查"""
        # 1. 语气词
        if word in self.config.interjections:
            return True

        # 1.1 包含多字语气词
        for intj in self.config.interjections:
            if len(intj) >= 2 and intj in word:
                return True

        # 2. 动作短语（以动词开头且长度>=2）
        if len(word) >= 2:
            for verb in self.config.action_verbs:
                if word.startswith(verb):
                    return True

        # 3. 含代词
        for pronoun in self.config.pronouns:
            if pronoun in word:
                return True

        # 4. 含数字/量词
        for num in self.config.numbers_quantifiers:
            if num in word:
                return True

        # 5. 亲属称呼
        if word in self.config.kinship_roles:
            return True

        return False

    def _extract_from_suffix_word(self, word: str) -> Optional[Tuple[str, str]]:
        """从带后缀的词中提取人名：返回 (人名, 后缀) 或 None"""
        if not self._suffix_pattern:
            return None
        match = self._suffix_pattern.match(word)
        if match:
            return (match.group(1), match.group(2))
        return None

    def _extract_from_prefix_word(self, word: str) -> Optional[Tuple[str, str]]:
        """从带前缀的词中提取人名：返回 (人名, 前缀) 或 None"""
        if not self._prefix_pattern:
            return None
        match = self._prefix_pattern.match(word)
        if match:
            return (match.group(2), match.group(1))
        return None

    def score_candidate(
        self,
        word: str,
        context: str,
        is_call_position: bool,
        has_affix: bool,
        occurrence_cache: Dict[str, int],
    ) -> Tuple[int, bool]:
        """
        对候选词打分
        返回: (分数, 是否满足强信号)
        """
        score = 0
        has_strong_signal = False
        word_len = len(word)

        # 规则1: 前缀/后缀称呼
        if has_affix:
            score += self.config.honorific_suffix_weight
            has_strong_signal = True

        # 规则2: 呼唤位
        if is_call_position and self.config.call_position_weight > 0:
            score += self.config.call_position_weight
            if 2 <= word_len <= 3:
                has_strong_signal = True

        # 规则3: 重复出现
        if self.config.repetition_weight > 0:
            if word not in occurrence_cache:
                occurrence_cache[word] = context.count(word)
            occurrences = occurrence_cache[word]

            if occurrences >= self.config.repetition_min_occurrences:
                score += self.config.repetition_weight
                if 2 <= word_len <= 3:
                    has_strong_signal = True

        # 规则4: 词性排除（负分）
        if word in self.config.pos_exclusion_words:
            score += self.config.pos_exclusion_weight

        # 规则5: 长度结构
        if word_len in self.config.length_preferred:
            exclude_ending = any(word.endswith(e) for e in self.config.length_exclude_endings)
            if not exclude_ending:
                score += self.config.length_structure_weight

        return score, has_strong_signal

    def is_name(
        self,
        word: str,
        context: str,
        is_call_position: bool = False,
        has_affix: bool = False,
        occurrence_cache: Optional[Dict[str, int]] = None,
    ) -> Tuple[bool, int]:
        """判断是否为人名，返回: (是否为人名, 分数)"""
        if occurrence_cache is None:
            occurrence_cache = {}

        # 白名单直接通过
        if word in self.config.known_names:
            return True, 999

        # 强排除
        if self.is_strongly_excluded(word):
            return False, -999

        # 黑名单
        if word in self.config.exclude_names:
            return False, -999

        # 打分
        score, has_strong_signal = self.score_candidate(
            word, context, is_call_position, has_affix, occurrence_cache
        )

        # 强信号检查
        if self.config.require_strong_signal and not has_strong_signal:
            return False, 0

        return score >= self.config.threshold, score

    def _is_at_boundary(self, text: str, start: int, end: int) -> bool:
        """检查词是否在边界位置"""
        at_start = (start == 0)
        at_end = (end == len(text))

        char_before = text[start - 1] if start > 0 else None
        char_after = text[end] if end < len(text) else None

        before_is_boundary = at_start or (char_before in self.BOUNDARY_CHARS)
        after_is_boundary = at_end or (char_after in self.BOUNDARY_CHARS)

        return before_is_boundary or after_is_boundary

    def _extract_candidates_from_part(
        self,
        part: str,
        full_context: str,
        is_first_part: bool,
        occurrence_cache: Dict[str, int],
    ) -> List[NameCandidate]:
        """从单个part中提取所有候选人名"""
        candidates = []

        for match in self.CHINESE_PATTERN.finditer(part):
            segment = match.group()
            segment_start = match.start()

            # 遍历所有可能的词（1-4字）
            for length in range(min(4, len(segment)), 0, -1):
                for i in range(len(segment) - length + 1):
                    word = segment[i:i + length]
                    word_start = segment_start + i
                    word_end = word_start + length
                    is_call_pos = (word_start == 0)  # part开头是呼唤位

                    # 1. 尝试匹配后缀模式（X哥、X姐）
                    suffix_result = self._extract_from_suffix_word(word)
                    if suffix_result:
                        name_part, suffix = suffix_result
                        name_start = word_start
                        name_end = word_start + len(name_part)

                        is_name, score = self.is_name(
                            name_part, full_context,
                            is_call_position=is_call_pos,
                            has_affix=True,
                            occurrence_cache=occurrence_cache,
                        )
                        if is_name:
                            candidates.append(NameCandidate(
                                name=name_part, start=name_start, end=name_end,
                                score=score, has_affix=True,
                                affix_type="suffix", affix=suffix,
                            ))
                        continue

                    # 2. 尝试匹配前缀模式（老X、小X、阿X）
                    prefix_result = self._extract_from_prefix_word(word)
                    if prefix_result:
                        name_part, prefix = prefix_result
                        name_start = word_start + len(prefix)
                        name_end = word_end

                        is_name, score = self.is_name(
                            name_part, full_context,
                            is_call_position=is_call_pos,
                            has_affix=True,
                            occurrence_cache=occurrence_cache,
                        )
                        if is_name:
                            candidates.append(NameCandidate(
                                name=name_part, start=name_start, end=name_end,
                                score=score, has_affix=True,
                                affix_type="prefix", affix=prefix,
                            ))
                        continue

                    # 3. 不带前后缀的普通词（1-3字）
                    if 1 <= length <= 3:
                        # 边界检查：必须在边界位置
                        if not self._is_at_boundary(part, word_start, word_end):
                            continue

                        is_name, score = self.is_name(
                            word, full_context,
                            is_call_position=is_call_pos,
                            has_affix=False,
                            occurrence_cache=occurrence_cache,
                        )
                        if is_name:
                            candidates.append(NameCandidate(
                                name=word, start=word_start, end=word_end,
                                score=score, has_affix=False,
                            ))

        return candidates

    def _select_non_overlapping(self, candidates: List[NameCandidate]) -> List[NameCandidate]:
        """贪心选择不重叠的候选（优先选择高分候选）"""
        if not candidates:
            return []

        sorted_candidates = sorted(candidates, key=lambda c: c.priority, reverse=True)
        selected = []
        used_ranges = []

        for candidate in sorted_candidates:
            overlaps = any(
                candidate.start < end and candidate.end > start
                for start, end in used_ranges
            )
            if not overlaps:
                selected.append(candidate)
                used_ranges.append((candidate.start, candidate.end))

        return selected

    def extract_and_replace_names(
        self,
        text: str,
        sep_marker: str = " <sep> ",
    ) -> Tuple[str, Dict[str, str], List[str]]:
        """
        从文本中提取人名并替换为占位符
        返回: (替换后的文本, {占位符: 人名}, [人名列表])
        """
        parts = text.split(sep_marker)
        name_map = {}
        name_to_placeholder = {}
        name_index = 0
        occurrence_cache = {}
        replaced_parts = []
        all_names = []

        for part_idx, part in enumerate(parts):
            candidates = self._extract_candidates_from_part(
                part, text, part_idx == 0, occurrence_cache
            )
            selected = self._select_non_overlapping(candidates)

            replacements = []
            for candidate in selected:
                name = candidate.name
                if name in name_to_placeholder:
                    placeholder = name_to_placeholder[name]
                else:
                    placeholder = self.config.name_placeholder_format.format(index=name_index)
                    name_map[placeholder] = name
                    name_to_placeholder[name] = placeholder
                    all_names.append(name)
                    name_index += 1
                replacements.append((candidate.start, candidate.end, placeholder))

            replaced_part = part
            for start, end, placeholder in sorted(replacements, key=lambda x: -x[0]):
                replaced_part = replaced_part[:start] + placeholder + replaced_part[end:]

            replaced_parts.append(replaced_part)

        replaced_text = sep_marker.join(replaced_parts)
        return replaced_text, name_map, all_names


def test_name_guard():
    """测试用例"""
    guard = NameGuard()

    test_cases = [
        ("平安，平安哥", "基础测试：同名带后缀"),
        ("平安哥，平安", "基础测试：后缀在前"),
        ("平安，平安，别这样", "重复出现"),
        ("平安哥，平安哥", "重复后缀"),
        ("老王，王师傅来了", "前缀+后缀"),
        ("小明说小红不在", "两个前缀名"),
        ("张三说李四不在", "两个普通名（白名单）"),
        ("阿强来找阿强", "前缀重复"),
    ]

    print("=" * 70)
    print("Name Guard V2 测试结果")
    print("=" * 70)

    for text, desc in test_cases:
        replaced, name_map, names = guard.extract_and_replace_names(text)
        print(f"\n【{desc}】")
        print(f"  原文: {text}")
        print(f"  替换: {replaced}")
        print(f"  人名: {names}")

    print("\n" + "=" * 70)


if __name__ == "__main__":
    test_name_guard()
