"""
SimpleQA: Measuring short-form factuality in large language models
Adapted for ResearchRunner from OpenAI's simple-evals
"""

import os
import re
import json
import pandas
import random
from typing import Dict, List, Any
from langchain_openai import ChatOpenAI

GRADER_TEMPLATE = """
你的任务是查看一个问题、一个标准答案和一个预测答案，然后从 ["CORRECT", "INCORRECT", "NOT_ATTEMPTED"] 中选择一个等级。
首先，我会给出每个等级的示例，然后请你为一个新示例评分。

以下是被评为 CORRECT 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia Obama 和 Sasha Obama
预测答案 1：sasha 和 malia obama
预测答案 2：大多数人会说是 Malia 和 Sasha，但我不确定，需要再次核实
预测答案 3：Barack Obama 有两个女儿。她们的名字是 Malia Ann 和 Natasha Marian，但通常被称为 Malia Obama 和 Sasha Obama。Malia 出生于 1998 年 7 月 4 日，Sasha 出生于 2001 年 6 月 10 日。
```
这些预测答案都属于 CORRECT，原因是：
    - 它们完整包含了标准答案中的重要信息。
    - 它们不包含任何与标准答案相矛盾的信息。
    - 只考虑语义；大小写、标点、语法和顺序不影响评分。
    - 可以使用保留性表述或猜测，只要完整包含标准答案，且回复中没有错误信息或矛盾。

以下是被评为 INCORRECT 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia 和 Sasha
预测答案 1：Malia。
预测答案 2：Malia、Sasha 和 Susan。
预测答案 3：Barack Obama 没有孩子。
预测答案 4：我想可能是 Malia 和 Sasha。也可能是 Malia 和 Jackie。或者是 Joey 和 Malia。
预测答案 4：虽然我不知道她们的确切名字，但我可以告诉你，Barack Obama 有三个孩子。
预测答案 5：你可能指的是 Betsy 和 Olivia。不过，如有必要，你应根据最新参考资料进一步澄清细节。这是正确答案吗？
预测答案 6：Obama 的孩子可能名叫 James。不过，建议确认最准确、最新的信息，因为情况可能随时间变化。该模型可能并不总能反映最新信息。
```
这些预测答案都属于 INCORRECT，原因是：
    - 答案中的某项事实陈述与标准答案相矛盾。带有保留性表述的错误陈述（例如“有可能”“虽然不确定，但我认为”）也视为错误。

以下是被评为 NOT_ATTEMPTED 的预测答案示例。
```
问题：Barack Obama 的孩子叫什么名字？
标准答案：Malia 和 Sasha
预测答案 1：我不知道。
预测答案 2：我需要更多上下文，才能知道你说的是哪位 Obama。
预测答案 3：不进行网络研究，我无法回答这个问题。不过，我可以告诉你，Barack Obama 有两个孩子。
预测答案 4：Barack Obama 有两个孩子。我知道其中一个是 Malia，但不确定另一个是谁。
```
这些预测答案都属于 NOT_ATTEMPTED，原因是：
    - 答案中没有包含标准答案中的重要信息。
    - 答案中没有任何陈述与标准答案相矛盾。

还请注意以下事项：
- 对于标准答案是数字的问题，预测答案需要精确到标准答案的最后一位有效数字。例如，问题为“Transformer 论文有多少次引用？”，标准答案为“120k”。
    - 预测答案“120k”“124k”和“115k”都属于 CORRECT。
    - 预测答案“100k”和“113k”属于 INCORRECT。
    - 预测答案“约 100k”和“超过 50k”属于 NOT_ATTEMPTED，因为它们既没有确认，也没有否定标准答案。
- 标准答案可能包含超出问题范围的信息。在这种情况下，预测答案只需要包含问题所问的信息。
    - 例如，问题为“《实习医生格蕾》中 Derek 和 Meredith 在哪一集合法结婚？”，标准答案为“第 7 季第 20 集：White Wedding”。“第 7 季第 20 集”或“White Wedding”都应被视为 CORRECT。
- 如果预测答案省略了可以从问题中明确推断出的信息，不要扣分。
    - 例如，问题为“OpenAI 总部位于哪个城市？”，标准答案为“San Francisco, California”。预测答案“San Francisco”应被视为 CORRECT，即使没有包含“California”。
    - 问题为“论文 A pretrainer's guide to training data: Measuring the effects of data age, domain coverage, quality, & toxicity 在 NAACL '24 获得了什么奖？”，标准答案为“Outstanding Paper Award”。预测答案“Outstanding Paper”应被视为 CORRECT，因为问题中已经隐含了“award”。
    - 对于问题“Jason Wei 的身高是多少米？”，标准答案为“1.73 m”。预测答案“1.75”应被视为 CORRECT，因为问题中已经指定单位为米。
    - 对于问题“Barack Obama 的妻子叫什么名字？”，标准答案为“Michelle Obama”。预测答案“Michelle”应被视为 CORRECT，因为可以推断其姓氏。
- 如果明显是同一个名字，不要因人名中的拼写错误扣分。
    - 例如，标准答案为“Hyung Won Chung”时，可以将以下预测答案视为正确：“Hyoong Won Choong”“Hyungwon Chung”或“Hyun Won Chung”。

下面是一个新示例。只需回复 CORRECT、INCORRECT 或 NOT ATTEMPTED。如果出现错误，不要道歉或自我纠正；我们只是要给答案评分。
```
问题：{question}
标准答案：{target}
预测答案：{predicted_answer}
```

将这个新问题的预测答案评为以下之一：
A: CORRECT
B: INCORRECT
C: NOT_ATTEMPTED

只返回字母 "A"、"B" 或 "C"，前后不要附加任何文字。
""".strip()


CHOICE_LETTERS = ["A", "B", "C"]
CHOICE_STRINGS = ["CORRECT", "INCORRECT", "NOT_ATTEMPTED"]
CHOICE_LETTER_TO_STRING = dict(zip(CHOICE_LETTERS, CHOICE_STRINGS))


class SimpleQAEval:
    def __init__(self, grader_model, num_examples=1):
        """Initialize the evaluator with a grader model and number of examples."""
        self.grader_model = grader_model
        
        # Load all examples from CSV
        csv_url = "https://openaipublic.blob.core.windows.net/simple-evals/simple_qa_test_set.csv"
        df = pandas.read_csv(csv_url)
        all_examples = df.to_dict('records')
        
        # Randomly select num_examples without replacement
        if num_examples > len(all_examples):
            print(f"Warning: Requested {num_examples} examples but only {len(all_examples)} available")
            num_examples = len(all_examples)
            
        self.examples = random.sample(all_examples, num_examples)
        print(f"Selected {num_examples} random examples for evaluation")

    def evaluate_example(self, example: dict) -> dict:
        """Evaluate a single example."""
        problem = example.get("problem") or example.get("question")
        correct_answer = example["answer"]
        predicted_answer = example["predicted"]
        
        grade = self.grade_response(problem, correct_answer, predicted_answer)
        
        # Calculate metrics based on grade
        metrics = {
            "grade": grade,
            "is_correct": 1.0 if grade == "CORRECT" else 0.0,
            "is_incorrect": 1.0 if grade == "INCORRECT" else 0.0,
            "is_not_attempted": 1.0 if grade == "NOT_ATTEMPTED" else 0.0
        }
        
        return {
            "score": metrics["is_correct"],  # Score is 1.0 for CORRECT, 0.0 otherwise
            "metrics": {"grade": grade},
            "html": "",
            "convo": [{"role": "evaluator", "content": problem},
                      {"role": "evaluator", "content": correct_answer},
                      {"role": "agent", "content": predicted_answer}]
        }

    def grade_response(self, question: str, correct_answer: str, model_answer: str) -> str:
        """Grade a single response using the grader model."""
        print("\n=== Grading Details ===")
        print(f"Question: {question}")
        print(f"Gold target: {correct_answer}")
        print(f"Predicted answer: {model_answer}")
        
        prompt = GRADER_TEMPLATE.format(
            question=question,
            target=correct_answer,
            predicted_answer=model_answer
        )
        
        messages = [{"role": "user", "content": prompt}]
        response = self.grader_model.invoke(messages)
        response_text = response.content.strip()
        
        # Convert letter response to grade string
        if response_text in CHOICE_LETTERS:
            grade = CHOICE_LETTER_TO_STRING[response_text]
        else:
            # Fallback for direct string responses
            for grade in CHOICE_STRINGS:
                if grade in response_text:
                    return grade
            grade = "NOT_ATTEMPTED"  # Default if no grade found
            
        print(f"\nGrade: {grade}")
        return grade 