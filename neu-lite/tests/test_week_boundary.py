import datetime as dt
import unittest

from neulite.jwxt import Jwxt


COURSE = {
    "course": "合同法与知识产权法",
    "day": 7,
    "weeks": [2, 3, 7, 8, 9, 10],
    "weeks_text": "2-3周,7-10周[理论]",
    "begin": "08:30",
    "end": "12:10",
    "place": "建筑A102",
    "teacher": "",
    "begin_section": 1,
    "end_section": 4,
    "color": "",
    "arranged": True,
}


class WeekBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.jwxt = object.__new__(Jwxt)
        self.jwxt.current_term = lambda: {
            "term": "2026-2027-1",
            "term_name": "2026-2027学年秋季学期",
            "week": 10,
            "date": "今天是11月2日 星期一",
        }
        self.jwxt.timetable = lambda: {"term": "2026-2027-1", "courses": [COURSE]}

    def test_sunday_belongs_to_new_week(self):
        # 第 10 周是 11/01（周日）—11/07（周六）
        week10 = self.jwxt.week_events(dt.date(2026, 11, 1))
        self.assertEqual(week10["week"], 10)
        self.assertTrue(any(e["title"] == COURSE["course"] for e in week10["events"]))

        # 11/08 是第 11 周周日，不能再归入第 10 周
        week11 = self.jwxt.week_events(dt.date(2026, 11, 8))
        self.assertEqual(week11["week"], 11)
        self.assertFalse(any(e["title"] == COURSE["course"] for e in week11["events"]))
        self.assertEqual(week11["start"], "2026-11-08")
        self.assertEqual(week11["end"], "2026-11-14")


if __name__ == "__main__":
    unittest.main()
