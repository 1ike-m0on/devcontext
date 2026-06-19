from app.models.ticket import Ticket
from app.repositories.ticket_repository import TicketRepository


class TicketService:
    def __init__(self):
        self.repository = TicketRepository()

    def create_ticket(self, payload):
        ticket = Ticket(title=payload["title"], priority=payload.get("priority", "normal"))
        return self.repository.save(ticket)
