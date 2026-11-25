"""Train the time-series adapter on numeric sequences with per-point labels."""

import argparse
import json
from pathlib import Path

import torch
from torch import nn


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--hidden-size", type=int, default=32)
    args = parser.parse_args()
    if args.epochs < 1 or args.hidden_size < 1:
        parser.error("epochs and hidden size must be positive")
    examples = json.loads(args.data.read_text(encoding="utf-8-sig"))
    if not examples:
        parser.error("training sequences are required")
    sequences = [torch.tensor(example["values"], dtype=torch.float32) for example in examples]
    if any(
        seq.ndim != 2 or not seq.numel() or not torch.isfinite(seq).all()
        or seq.shape[1] != sequences[0].shape[-1] or len(seq) != len(example["labels"])
        for seq, example in zip(sequences, examples)
    ):
        parser.error("expected finite time-by-channel matrices with one label per row")

    class_names = sorted({label for example in examples for label in example["labels"] if label is not None})
    if not class_names or any(not isinstance(label, str) or not label for label in class_names):
        parser.error("at least one nonempty project label is required")
    labels = [None, *class_names]
    targets = [
        torch.tensor([labels.index(label) for label in example["labels"]], dtype=torch.long)
        for example in examples
    ]
    values = torch.cat(sequences)
    mean = values.mean(dim=0)
    scale = values.std(dim=0, unbiased=False)
    scale[scale == 0] = 1
    input_size = values.shape[1]
    torch.manual_seed(0)
    lstm = nn.LSTM(input_size, args.hidden_size, batch_first=True)
    classifier = nn.Linear(args.hidden_size, len(labels))
    optimizer = torch.optim.Adam([*lstm.parameters(), *classifier.parameters()])
    loss_fn = nn.CrossEntropyLoss()
    for _ in range(args.epochs):
        for seq, target in zip(sequences, targets):
            optimizer.zero_grad()
            features, _ = lstm(((seq - mean) / scale).unsqueeze(0))
            loss = loss_fn(classifier(features)[0], target)
            loss.backward()
            optimizer.step()
    with args.output.open("xb") as output:
        torch.save(dict(
            inputSize=input_size, hiddenSize=args.hidden_size, labels=labels,
            mean=mean, scale=scale, lstm=lstm.state_dict(), classifier=classifier.state_dict(),
        ), output)


if __name__ == "__main__":
    main()
